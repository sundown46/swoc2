package io.swoc2.app.picture;

import io.swoc2.app.settings.InstanceSettings;
import io.swoc2.app.settings.InstanceSettingsService;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.ContactKind;
import io.swoc2.domain.picture.ContactState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Aging (PIC-004, MAP-019): a contact not updated for {@code staleAfter} turns STALE, after
 * {@code deleteAfter} it is removed. The global default comes from the instance settings;
 * per-connection overrides (connection manager, M3/M8) plug in via {@link #setPolicy}. OWNUNITs
 * age like contacts; user-created contacts never age.
 */
@Component
public class AgingService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgingService.class);

    private final PictureStore store;
    private final InstanceSettingsService settings;
    private final Clock clock;
    private volatile Function<String, InstanceSettings.Aging> policy;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "picture-aging");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    AgingService(PictureStore store, InstanceSettingsService settings) {
        this(store, settings, Clock.systemUTC(), true);
    }

    AgingService(PictureStore store, InstanceSettingsService settings, Clock clock, boolean schedule) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.policy = connectionId -> settings.current().aging();
        if (schedule) {
            scheduler.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.SECONDS);
        }
    }

    /** Aging policy per connection id; defaults to the global instance setting. */
    public void setPolicy(Function<String, InstanceSettings.Aging> perConnection) {
        this.policy = connectionId -> {
            InstanceSettings.Aging specific = perConnection.apply(connectionId);
            return specific != null ? specific : settings.current().aging();
        };
    }

    /** One pass over the picture; scheduled every second. */
    void sweep() {
        try {
            Instant now = clock.instant();
            for (Contact c : store.sourceValues()) {
                if (c.kind() == ContactKind.USER_CONTACT) {
                    continue;
                }
                InstanceSettings.Aging aging = policy.apply(c.key().connectionId());
                Duration age = Duration.between(c.receivedAt(), now);
                if (age.compareTo(aging.deleteAfter()) >= 0) {
                    store.remove(c.key(), c.receivedAt());
                } else if (age.compareTo(aging.staleAfter()) >= 0 && c.state() == ContactState.LIVE) {
                    store.markStale(c.key(), c.receivedAt());
                }
            }
        } catch (RuntimeException e) {
            // Must never kill the scheduler, or nothing would ever age again.
            log.error("Aging sweep failed", e);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
