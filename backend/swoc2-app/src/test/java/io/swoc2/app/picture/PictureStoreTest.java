package io.swoc2.app.picture;

import static org.assertj.core.api.Assertions.assertThat;

import io.swoc2.domain.geo.GeoPosition;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.ContactKind;
import io.swoc2.domain.picture.ContactOverride;
import io.swoc2.domain.picture.ContactState;
import io.swoc2.domain.picture.ContactUpdate;
import io.swoc2.domain.picture.Identity;
import io.swoc2.domain.picture.SourceKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Picture store semantics (PIC-001/003/005/009, ARCHITECTURE §5.2), without Spring. */
class PictureStoreTest {

    private final PictureStore store = new PictureStore();
    private final List<PictureChange> changes = new ArrayList<>();
    private final Instant t0 = Instant.parse("2026-10-03T12:00:00Z");

    PictureStoreTest() {
        store.addListener(changes::add);
    }

    static ContactUpdate update(String track, double lat, double lon, String sidc, String name) {
        return new ContactUpdate(
                new SourceKey("conn-1", "SENDER", track),
                "SEDAP_X",
                "R",
                ContactKind.CONTACT,
                sidc,
                name,
                GeoPosition.of(lat, lon),
                90.0,
                null,
                5.0,
                null,
                'U',
                Map.of(),
                Map.of("Comment", "x"));
    }

    @Test
    void sameSourceKeyIsTheSameContactAcrossUpdates() {
        Contact first = store.upsert(update("100", 53.5, 8.1, "SFSPCLFF-------", "FGS Bayern"), t0);
        Contact second = store.upsert(update("100", 53.6, 8.2, null, null), t0.plusSeconds(1));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.size()).isEqualTo(1);
        assertThat(second.position().latitude()).isEqualTo(53.6);
        // Descriptive values not reported again are kept; kinematics are as reported.
        assertThat(second.name()).isEqualTo("FGS Bayern");
        assertThat(second.identity()).isEqualTo(Identity.FRIEND);
        assertThat(second.state()).isEqualTo(ContactState.LIVE);
    }

    @Test
    void cellIndexFollowsMovement() {
        Contact c = store.upsert(update("1", 53.5, 8.1, null, null), t0);
        assertThat(store.inCells(List.of("32UME"))).extracting(Contact::id).containsExactly(c.id());

        store.upsert(update("1", 48.1371, 11.5754, null, null), t0.plusSeconds(1));

        assertThat(store.inCells(List.of("32UME"))).isEmpty();
        assertThat(store.inCells(List.of("32UPU"))).hasSize(1);
        assertThat(changes.getLast().previousCell()).isEqualTo("32UME");
    }

    @Test
    void overrideWinsOverSourceAndSurvivesUpdatesAndResetRestoresSource() {
        Contact c = store.upsert(update("1", 53.5, 8.1, "SFSPCLFF-------", "Source name"), t0);
        store.setOverride(c.key(), new ContactOverride("Operator name", "watch it", Identity.HOSTILE, null));

        Contact afterUpdate =
                store.upsert(update("1", 53.51, 8.1, "SFSPCLFF-------", "New source name"), t0.plusSeconds(5));
        assertThat(afterUpdate.name()).isEqualTo("Operator name");
        assertThat(afterUpdate.identity()).isEqualTo(Identity.HOSTILE);
        assertThat(afterUpdate.remarks()).isEqualTo("watch it");
        assertThat(afterUpdate.overridden().name()).isEqualTo("Operator name");

        store.setOverride(c.key(), ContactOverride.NONE);
        Contact reset = store.get(c.id()).orElseThrow();
        assertThat(reset.name()).isEqualTo("New source name");
        assertThat(reset.identity()).isEqualTo(Identity.FRIEND);
    }

    @Test
    void sidcOverrideChangesIdentityAndDimensionToo() {
        Contact c = store.upsert(update("1", 53.5, 8.1, "SFSPCLFF-------", null), t0);
        store.setOverride(c.key(), new ContactOverride(null, null, null, "SHAPMF---------"));

        Contact r = store.get(c.id()).orElseThrow();
        assertThat(r.symbol().code()).isEqualTo("SHAPMF---------");
        assertThat(r.identity()).isEqualTo(Identity.HOSTILE);
        assertThat(r.dimension().name()).isEqualTo("AIR");
    }

    @Test
    void staleAndRemoveOnlyApplyIfTheContactWasNotUpdatedMeanwhile() {
        Contact c = store.upsert(update("1", 53.5, 8.1, null, null), t0);
        store.upsert(update("1", 53.5, 8.1, null, null), t0.plusSeconds(10)); // fresh report

        store.markStale(c.key(), t0);
        assertThat(store.remove(c.key(), t0)).isFalse();

        assertThat(store.get(c.id()).orElseThrow().state()).isEqualTo(ContactState.LIVE);
        store.markStale(c.key(), t0.plusSeconds(10));
        assertThat(store.get(c.id()).orElseThrow().state()).isEqualTo(ContactState.STALE);
        assertThat(changes.getLast().contact().state()).isEqualTo(ContactState.STALE);
    }

    @Test
    void wipeRemovesEverythingButUserContacts() {
        store.upsert(update("1", 53.5, 8.1, null, null), t0);
        store.upsert(update("2", 53.6, 8.1, null, null), t0);
        ContactUpdate user = new ContactUpdate(
                new SourceKey("user", "", "u1"),
                "USER",
                null,
                ContactKind.USER_CONTACT,
                null,
                "Mine",
                GeoPosition.of(53, 8),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        store.upsert(user, t0);

        assertThat(store.wipe()).isEqualTo(2);
        assertThat(store.all()).extracting(Contact::kind).containsExactly(ContactKind.USER_CONTACT);
        assertThat(changes)
                .filteredOn(ch -> ch.type() == PictureChange.Type.REMOVE)
                .hasSize(2);
    }

    @Test
    void highestClassificationOfDisplayedData() {
        assertThat(store.highestClassification()).isNull();
        store.upsert(update("1", 53.5, 8.1, null, null), t0); // U
        ContactUpdate secret = new ContactUpdate(
                new SourceKey("conn-1", "S", "2"),
                null,
                null,
                ContactKind.CONTACT,
                null,
                null,
                GeoPosition.of(53, 8),
                null,
                null,
                null,
                null,
                'S',
                null,
                null);
        store.upsert(secret, t0);
        assertThat(store.highestClassification()).isEqualTo('S');
    }

    @Test
    void aFailingListenerDoesNotBreakIngest() {
        store.addListener(change -> {
            throw new IllegalStateException("listener bug");
        });
        store.upsert(update("1", 53.5, 8.1, null, null), t0);
        assertThat(store.size()).isEqualTo(1);
    }

    /** Sanity check towards NFR-003 (the real load test uses external tools). */
    @Test
    void handles100kContactsAndUpdatesQuickly() {
        PictureStore big = new PictureStore();
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            big.upsert(update(Integer.toString(i), 47 + (i % 1300) / 100.0, -4 + (i % 2800) / 100.0, null, null), t0);
        }
        for (int i = 0; i < 100_000; i++) {
            big.upsert(update(Integer.toString(i), 47 + (i % 1299) / 100.0, -4 + (i % 2799) / 100.0, null, null), t0);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(big.size()).isEqualTo(100_000);
        // 200k upserts incl. MGRS cell computation; generous bound for slow CI machines.
        assertThat(took).isLessThan(Duration.ofSeconds(20));
        System.out.println("PictureStore: 200k upserts in " + took.toMillis() + " ms");
    }
}
