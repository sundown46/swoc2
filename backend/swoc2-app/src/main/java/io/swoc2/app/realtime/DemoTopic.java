package io.swoc2.app.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spike-only {@code demo} topic ({@code docs/realtime-protocol.md} §10): synthetic contacts that
 * move every batch interval while anyone is subscribed. Units as everywhere: degrees, m/s, degrees
 * true. Replaced by the real picture topics in P1.
 */
@Component
class DemoTopic implements Topic, DisposableBean {

    static final String NAME = "demo";
    private static final Logger log = LoggerFactory.getLogger(DemoTopic.class);
    private static final double METRES_PER_DEGREE = 111_320;

    private record Contact(int id, double lat, double lon, double course, double speed) {}

    private final RealtimeSessionRegistry registry;
    private final List<Contact> contacts = new ArrayList<>();
    private final double dtSeconds;
    private final Random random = new Random(42);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "realtime-demo-topic");
        t.setDaemon(true);
        return t;
    });

    DemoTopic(RealtimeSessionRegistry registry, RealtimeProperties properties) {
        this.registry = registry;
        this.dtSeconds = properties.batchInterval().toMillis() / 1000.0;
        for (int i = 0; i < properties.demoContacts(); i++) {
            contacts.add(new Contact(
                    i,
                    53 + random.nextDouble() * 4,
                    4 + random.nextDouble() * 10,
                    random.nextDouble() * 360,
                    5 + random.nextDouble() * 20));
        }
        long interval = properties.batchInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized JsonNode snapshot() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("topic", NAME);
        ArrayNode items = payload.putArray("items");
        contacts.forEach(c -> items.add(json(c)));
        return payload;
    }

    @Override
    public synchronized void sendSnapshot(RealtimeSession session) {
        session.publish(Envelope.SNAPSHOT, snapshot());
    }

    private synchronized void tick() {
        try {
            if (!registry.hasSubscribers(NAME) || contacts.isEmpty()) {
                return;
            }
            ObjectNode payload = JsonNodeFactory.instance.objectNode().put("topic", NAME);
            ArrayNode upserts = payload.putArray("upserts");
            payload.putArray("removes");
            // A fifth of the contacts move per batch, like a throttled server delta.
            int count = Math.max(1, contacts.size() / 5);
            for (int n = 0; n < count; n++) {
                int i = random.nextInt(contacts.size());
                Contact c = contacts.get(i);
                double rad = Math.toRadians(c.course());
                double dist = c.speed() * dtSeconds * 50; // exaggerated so movement is visible
                double lat = c.lat() + dist * Math.cos(rad) / METRES_PER_DEGREE;
                double lon = c.lon() + dist * Math.sin(rad) / (METRES_PER_DEGREE * Math.cos(Math.toRadians(lat)));
                double course = (lat < 50 || lat > 60 || lon < 0 || lon > 20) ? (c.course() + 180) % 360 : c.course();
                Contact moved = new Contact(c.id(), lat, lon, course, c.speed());
                contacts.set(i, moved);
                upserts.add(json(moved));
            }
            registry.publish(NAME, Envelope.DELTA, payload);
        } catch (RuntimeException unexpected) {
            log.error("Demo topic tick failed", unexpected);
        }
    }

    private static ObjectNode json(Contact c) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("id", c.id())
                .put("lat", c.lat())
                .put("lon", c.lon())
                .put("course", c.course())
                .put("speed", c.speed());
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
