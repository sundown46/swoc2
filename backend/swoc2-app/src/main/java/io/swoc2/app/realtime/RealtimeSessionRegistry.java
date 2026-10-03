package io.swoc2.app.realtime;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * All realtime sessions of this instance. Creates sessions (enforcing the per-user limit),
 * resolves them for their owner only, fans topic envelopes out to subscribers, sends idle
 * heartbeats and expires abandoned sessions ({@code docs/realtime-protocol.md} §2, §7, §9).
 */
@Component
class RealtimeSessionRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();
    private final RealtimeProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final java.util.concurrent.atomic.AtomicLong serials = new java.util.concurrent.atomic.AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "realtime-housekeeping");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    RealtimeSessionRegistry(RealtimeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RealtimeSessionRegistry(RealtimeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        long tick = Math.max(250, Math.min(properties.heartbeat().toMillis(), 1000));
        scheduler.scheduleWithFixedDelay(this::housekeeping, tick, tick, TimeUnit.MILLISECONDS);
    }

    RealtimeSession create(String owner) {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RealtimeSession session =
                new RealtimeSession(id, owner, serials.incrementAndGet(), properties.bufferSize(), clock);
        sessions.put(id, session);
        enforceLimit(owner);
        session.publish(
                Envelope.HELLO,
                JsonNodeFactory.instance
                        .objectNode()
                        .put("sessionId", id)
                        .put("heartbeatMs", properties.heartbeat().toMillis()));
        return session;
    }

    private void enforceLimit(String owner) {
        List<RealtimeSession> own = sessions.values().stream()
                .filter(s -> s.owner().equals(owner))
                .sorted(Comparator.comparingLong(RealtimeSession::serial))
                .toList();
        int excess = own.size() - properties.maxSessionsPerUser();
        own.stream().limit(Math.max(0, excess)).forEach(s -> remove(s, "session-limit"));
    }

    /** Resolves a session for its owner; unknown, expired and foreign look the same. */
    RealtimeSession get(String id, String owner) {
        RealtimeSession session = id == null ? null : sessions.get(id);
        if (session == null || session.closed() || !session.owner().equals(owner)) {
            throw new UnknownSessionException();
        }
        return session;
    }

    /** Sends an envelope to every session subscribed to {@code topic}. */
    void publish(String topic, String type, JsonNode payload) {
        for (RealtimeSession session : sessions.values()) {
            if (session.isSubscribed(topic)) {
                session.publish(type, payload);
            }
        }
    }

    boolean hasSubscribers(String topic) {
        return sessions.values().stream().anyMatch(s -> s.isSubscribed(topic));
    }

    int size() {
        return sessions.size();
    }

    private void remove(RealtimeSession session, String code) {
        sessions.remove(session.id());
        session.publish(
                Envelope.ERROR,
                JsonNodeFactory.instance.objectNode().put("code", code).put("message", "Realtime session closed"));
        session.close(4404, code);
    }

    private void housekeeping() {
        try {
            long ttl = properties.sessionTtl().toMillis();
            long heartbeat = properties.heartbeat().toMillis();
            for (RealtimeSession session : sessions.values()) {
                if (session.expired(ttl)) {
                    sessions.remove(session.id());
                    session.close(4404, "expired");
                } else if (session.idleFor(heartbeat)) {
                    session.heartbeat(false);
                }
            }
        } catch (RuntimeException unexpected) {
            // Must never kill the scheduler thread, or heartbeats and expiry stop for everyone.
            log.error("Realtime housekeeping failed", unexpected);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
