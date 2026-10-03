package io.swoc2.app.connections.sedap;

import io.swoc2.app.connections.ConnectionRuntime;
import io.swoc2.app.connections.FrameHandler;
import io.swoc2.app.connections.debug.DebugTap;
import io.swoc2.app.picture.PictureStore;
import io.swoc2.app.settings.InstanceSettingsService;
import io.swoc2.domain.geo.GeoPosition;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.SourceKey;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.codec.DecodeResult;
import io.swoc2.sedap.codec.SedapDecoder;
import io.swoc2.sedap.codec.SedapMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Inbound SEDAP-Express pipeline (ARCHITECTURE §4): decode, debug tap, loop prevention (own sender
 * dropped, dedup cache - CON-005), OWNUNIT routing table (CON-007), then by type: CONTACT/OWNUNIT
 * into the live picture, HEARTBEAT to connection health, TEXT as an application event for the chat.
 */
@Component
public class SedapIngest implements FrameHandler {

    private final SedapDecoder decoder = new SedapDecoder();
    private final DedupCache dedup = new DedupCache(Duration.ofSeconds(60), 200_000);
    private final PictureStore picture;
    private final InstanceSettingsService settings;
    private final DebugTap debug;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();
    /** OWNUNIT sender -> connection it was last received on (CON-007, used for COMMAND delivery). */
    private final Map<String, String> ownUnitRoutes = new ConcurrentHashMap<>();

    SedapIngest(
            PictureStore picture, InstanceSettingsService settings, DebugTap debug, ApplicationEventPublisher events) {
        this.picture = picture;
        this.settings = settings;
        this.debug = debug;
        this.events = events;
    }

    @Override
    public String frameFormat() {
        return ConnectionType.SEDAP_EXPRESS;
    }

    @Override
    public void handle(ConnectionRuntime connection, String frame, Map<String, String> meta) {
        Instant now = clock.instant();
        String connectionId = connection.connectionId();
        DecodeResult result = decoder.decode(frame);
        debug.inbound(connectionId, connection.definition().name(), frame, result, meta);
        if (result.rejected()) {
            connection.error("rejected: " + result.warnings().getFirst().message());
            return;
        }
        if (!result.warnings().isEmpty()) {
            connection.warning();
        }
        SedapMessage m = result.message();
        String sender = m.header().sender();
        if (sender != null && sender.equalsIgnoreCase(settings.current().senderId())) {
            connection.dropped(); // our own message came back (CON-005)
            return;
        }
        if (m.header().number() != null && m.header().time() != null && sender != null) {
            String key = sender + "|" + m.type() + "|" + m.header().number() + "|"
                    + m.header().time().toEpochMilli();
            if (dedup.isDuplicate(key, now)) {
                connection.dropped(); // seen via another path (multi-hop loop, CON-005)
                return;
            }
        }
        switch (m.type()) {
            case CONTACT, OWNUNIT -> toPicture(connection, m, now);
            case HEARTBEAT -> connection.heartbeat();
            case TEXT -> events.publishEvent(new SedapTextReceived(connectionId, m, now));
            default -> {
                // Other types are visible in the debug console; their features come in P2/P3.
            }
        }
    }

    private void toPicture(ConnectionRuntime connection, SedapMessage m, Instant now) {
        String connectionId = connection.connectionId();
        if (m.type() == MessageType.OWNUNIT) {
            ownUnitRoutes.put(SedapContactMapper.ownUnitTrack(m), connectionId);
        }
        SedapContactMapper.Result r =
                SedapContactMapper.map(connectionId, m, sender -> reference(connectionId, sender));
        switch (r) {
            case SedapContactMapper.Upsert u -> picture.upsert(u.update(), now);
            case SedapContactMapper.Delete d -> picture.removeBySource(d.key());
            case SedapContactMapper.Unusable bad -> {
                connection.warning();
                debug.note(connectionId, bad.reason());
            }
        }
    }

    /** ICD §6.2: relative positions refer to the sender's OWNUNIT, else to our own position. */
    private Optional<GeoPosition> reference(String connectionId, String sender) {
        Optional<GeoPosition> ownUnit = picture.get(
                        new SourceKey(connectionId, sender, sender.isEmpty() ? "OWNUNIT" : sender))
                .map(Contact::position);
        if (ownUnit.isPresent()) {
            return ownUnit;
        }
        var own = settings.current().ownPosition();
        return own == null
                ? Optional.empty()
                : Optional.of(new GeoPosition(own.latitude(), own.longitude(), own.altitude()));
    }

    /** Connection an OWNUNIT was last heard on (CON-007), for COMMAND delivery. */
    public Optional<String> routeForOwnUnit(String sender) {
        return Optional.ofNullable(ownUnitRoutes.get(sender));
    }

    /** A TEXT message arrived (SDX-006); the chat feature (M7) listens for it. */
    public record SedapTextReceived(String connectionId, SedapMessage message, Instant receivedAt) {}
}
