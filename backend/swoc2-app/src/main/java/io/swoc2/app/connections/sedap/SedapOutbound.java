package io.swoc2.app.connections.sedap;

import io.swoc2.app.connections.ConnectionRuntime;
import io.swoc2.app.connections.ConnectionService;
import io.swoc2.app.connections.debug.DebugTap;
import io.swoc2.app.settings.InstanceSettingsService;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.codec.Header;
import io.swoc2.sedap.codec.SedapEncoder;
import io.swoc2.sedap.codec.SedapMessage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Outbound SEDAP-Express (ARCHITECTURE §4 "Outbound", §11.3): stamps every message with our sender
 * ID (SDX-004), the per-type 7-bit message number (ICD §5: one counter per type, wraps after 7F,
 * not reset by reconnects) and the current time, encodes it and sends it to the selected
 * connections - never back to the connection it came from (CON-005). Also sends our HEARTBEAT at
 * 1 Hz on every outbound-capable SEDAP connection (ICD §2).
 */
@Component
public class SedapOutbound implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SedapOutbound.class);

    private final ConnectionService connections;
    private final InstanceSettingsService settings;
    private final SedapIngest ingest;
    private final DebugTap debug;
    private final Clock clock = Clock.systemUTC();
    private final Map<MessageType, Integer> numbers = new EnumMap<>(MessageType.class);
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sedap-heartbeat");
        t.setDaemon(true);
        return t;
    });

    SedapOutbound(ConnectionService connections, InstanceSettingsService settings, SedapIngest ingest, DebugTap debug) {
        this.connections = connections;
        this.settings = settings;
        this.ingest = ingest;
        this.debug = debug;
        heartbeat.scheduleWithFixedDelay(this::sendHeartbeats, 1, 1, TimeUnit.SECONDS);
    }

    /** Where to send. */
    public sealed interface Target {}

    /** Every outbound-capable SEDAP connection, except {@code exclude} (the origin, CON-005). */
    public record AllConnections(String exclude) implements Target {}

    /** One connection. */
    public record Connection(UUID id) implements Target {}

    /**
     * The connection an OWNUNIT was last heard on (CON-007); falls back to all connections if it was
     * never heard.
     */
    public record OwnUnit(String sender) implements Target {}

    /** Outcome per connection, for the caller and the API. */
    public record Sent(String line, List<String> deliveredTo, List<String> failed) {}

    /** Next per-type number (0..7F, wrapping). */
    synchronized int nextNumber(MessageType type) {
        int n = (numbers.getOrDefault(type, -1) + 1) & 0x7F;
        numbers.put(type, n);
        return n;
    }

    /**
     * Sends a message. Header number, time and sender are always ours; classification and the
     * acknowledgement flag are kept from the given message.
     */
    public Sent send(SedapMessage message, Target target) {
        Header h = message.header();
        Header ours = new Header(
                nextNumber(message.type()),
                clock.instant(),
                settings.current().senderId(),
                h.classification(),
                h.acknowledgement(),
                null);
        String line = SedapEncoder.encode(message.withHeader(ours));
        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (ConnectionRuntime c : targets(target)) {
            if (c.send(line)) {
                delivered.add(c.definition().name());
                debug.outbound(c.connectionId(), c.definition().name(), line);
            } else {
                failed.add(c.definition().name());
            }
        }
        return new Sent(line, delivered, failed);
    }

    private List<ConnectionRuntime> targets(Target target) {
        List<ConnectionRuntime> outbound = connections.runtimes().stream()
                .filter(c -> c.definition().direction() != Direction.IN)
                .filter(c -> ConnectionType.SEDAP_EXPRESS.equals(c.frameFormat()))
                .toList();
        return switch (target) {
            case AllConnections all ->
                outbound.stream()
                        .filter(c -> !c.connectionId().equals(all.exclude()))
                        .toList();
            case Connection one ->
                outbound.stream()
                        .filter(c -> c.definition().id().equals(one.id()))
                        .toList();
            case OwnUnit unit -> {
                Optional<String> route = ingest.routeForOwnUnit(unit.sender());
                yield route.map(id -> outbound.stream()
                                .filter(c -> c.connectionId().equals(id))
                                .toList())
                        .orElse(outbound);
            }
        };
    }

    private void sendHeartbeats() {
        try {
            String sender = settings.current().senderId();
            for (ConnectionRuntime c : connections.runtimes()) {
                if (c.definition().direction() != Direction.IN
                        && ConnectionType.SEDAP_EXPRESS.equals(c.frameFormat())
                        && (c.state() == ConnectionState.UP || c.state() == ConnectionState.DEGRADED)) {
                    Header h =
                            new Header(nextNumber(MessageType.HEARTBEAT), clock.instant(), sender, null, false, null);
                    c.send(SedapEncoder.encode(
                            SedapMessage.builder(MessageType.HEARTBEAT).build().withHeader(h)));
                }
            }
        } catch (RuntimeException e) {
            log.error("Heartbeat round failed", e);
        }
    }

    @Override
    public void destroy() {
        heartbeat.shutdownNow();
    }
}
