package io.swoc2.app.picture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.swoc2.app.settings.InstanceSettings;
import io.swoc2.app.settings.InstanceSettingsService;
import io.swoc2.domain.geo.GeoPosition;
import io.swoc2.domain.picture.ContactKind;
import io.swoc2.domain.picture.ContactState;
import io.swoc2.domain.picture.ContactUpdate;
import io.swoc2.domain.picture.SourceKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Aging (PIC-004, MAP-019) with a controlled clock. */
class AgingServiceTest {

    private Instant now = Instant.parse("2026-10-03T12:00:00Z");
    private final PictureStore store = new PictureStore();
    private final InstanceSettingsService settings = mock(InstanceSettingsService.class);
    private final AgingService aging;

    AgingServiceTest() {
        when(settings.current())
                .thenReturn(new InstanceSettings(
                        "SWOC2",
                        null,
                        new InstanceSettings.Aging(Duration.ofSeconds(60), Duration.ofSeconds(300)),
                        new InstanceSettings.DebugConsole(false, Set.of("admin"))));
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        aging = new AgingService(store, settings, clock, false);
    }

    private void report(String connection, String track, ContactKind kind) {
        store.upsert(
                new ContactUpdate(
                        new SourceKey(connection, "S", track),
                        "SEDAP_X",
                        null,
                        kind,
                        null,
                        null,
                        GeoPosition.of(53.5, 8.1),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                now);
    }

    @Test
    void liveThenStaleThenRemoved() {
        report("c1", "1", ContactKind.CONTACT);
        report("c1", "own", ContactKind.OWNUNIT);

        now = now.plusSeconds(59);
        aging.sweep();
        assertThat(store.all()).allMatch(c -> c.state() == ContactState.LIVE);

        now = now.plusSeconds(1);
        aging.sweep();
        assertThat(store.all()).hasSize(2).allMatch(c -> c.state() == ContactState.STALE);

        now = now.plusSeconds(240);
        aging.sweep();
        assertThat(store.all()).isEmpty(); // OWNUNITs age too (PIC-004)
    }

    @Test
    void anUpdateMakesAStaleContactLiveAgain() {
        report("c1", "1", ContactKind.CONTACT);
        now = now.plusSeconds(61);
        aging.sweep();
        report("c1", "1", ContactKind.CONTACT);
        assertThat(store.all().getFirst().state()).isEqualTo(ContactState.LIVE);
    }

    @Test
    void userContactsNeverAge() {
        report("user", "u1", ContactKind.USER_CONTACT);
        now = now.plus(Duration.ofDays(1));
        aging.sweep();
        assertThat(store.all()).hasSize(1).allMatch(c -> c.state() == ContactState.LIVE);
    }

    @Test
    void perConnectionPolicyOverridesTheDefault() {
        aging.setPolicy(connection -> connection.equals("fast")
                ? new InstanceSettings.Aging(Duration.ofSeconds(5), Duration.ofSeconds(10))
                : null);
        report("fast", "1", ContactKind.CONTACT);
        report("slow", "1", ContactKind.CONTACT);

        now = now.plusSeconds(10);
        aging.sweep();

        assertThat(store.all()).extracting(c -> c.key().connectionId()).containsExactly("slow");
    }
}
