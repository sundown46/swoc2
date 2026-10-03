package io.swoc2.app.connections;

import io.swoc2.app.audit.AuditLog;
import io.swoc2.app.settings.InstanceSettings;
import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Manages connection definitions (CON-001) and their runtimes: persists and audits every change,
 * starts enabled connections at boot, restarts a connection when its definition changes, and runs
 * the "test before saving" check.
 */
@Service
public class ConnectionService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    static final String SECRET_MASK = "********";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditLog auditLog;
    private final ConnectionTypeRegistry types;
    private final SecretBox secrets;
    private final Map<String, FrameHandler> handlers = new HashMap<>();
    private final Map<UUID, ConnectionRuntime> runtimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "connection-backoff");
        t.setDaemon(true);
        return t;
    });
    private final Clock clock = Clock.systemUTC();

    ConnectionService(
            JdbcClient jdbc,
            ObjectMapper mapper,
            AuditLog auditLog,
            ConnectionTypeRegistry types,
            List<FrameHandler> frameHandlers,
            io.swoc2.app.picture.AgingService aging,
            SecretBox secrets) {
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auditLog = auditLog;
        this.types = types;
        frameHandlers.forEach(h -> handlers.put(h.frameFormat(), h));
        // Per-connection aging overrides (PIC-004); null falls back to the instance default.
        aging.setPolicy(this::agingFor);
        for (ConnectionDefinition d : loadAll()) {
            ConnectionRuntime r = runtimeFor(d);
            if (r != null) {
                runtimes.put(d.id(), r);
                if (d.enabled()) {
                    r.start();
                }
            }
        }
    }

    private List<ConnectionDefinition> loadAll() {
        return jdbc
                .sql(
                        "SELECT id, name, type, enabled, direction, config::text AS config, aging::text AS aging FROM connection ORDER BY name")
                .query((rs, n) -> {
                    try {
                        return new ConnectionDefinition(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("type"),
                                rs.getBoolean("enabled"),
                                Direction.valueOf(rs.getString("direction")),
                                mapper.readValue(rs.getString("config"), new TypeReference<Map<String, Object>>() {}),
                                rs.getString("aging") == null
                                        ? null
                                        : mapper.readValue(rs.getString("aging"), InstanceSettings.Aging.class));
                    } catch (RuntimeException unreadable) {
                        log.error("Skipping unreadable connection {}", rs.getString("name"), unreadable);
                        return null;
                    }
                })
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ConnectionRuntime runtimeFor(ConnectionDefinition d) {
        Optional<ConnectionType> type = types.get(d.type());
        if (type.isEmpty()) {
            log.error("Connection {} has unknown type {} (plugin missing?); not started", d.name(), d.type());
            return null;
        }
        FrameHandler handler = handlers.get(type.get().frameFormat());
        if (handler == null) {
            log.error(
                    "No decoder for frame format {} of connection {}",
                    type.get().frameFormat(),
                    d.name());
            return null;
        }
        ConnectionDefinition plain;
        try {
            plain = withConfig(d, decryptSecrets(d.type(), d.config()));
        } catch (IllegalStateException e) {
            log.error("Connection {} not started: {}", d.name(), e.getMessage());
            return null;
        }
        return new ConnectionRuntime(plain, type.get(), handler, scheduler, clock);
    }

    private static ConnectionDefinition withConfig(ConnectionDefinition d, Map<String, Object> config) {
        return new ConnectionDefinition(d.id(), d.name(), d.type(), d.enabled(), d.direction(), config, d.aging());
    }

    /** Secret fields encrypted for storage (never plaintext in the DB). */
    private Map<String, Object> encryptSecrets(String typeId, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config);
        for (String field : types.secretFields(typeId)) {
            Object v = out.get(field);
            if (v instanceof String s && !s.isEmpty() && !SecretBox.isEncrypted(s)) {
                if (!secrets.available()) {
                    throw new InvalidConnectionException(
                            Map.of(field, "cannot be saved: SWOC2_SECRET_KEY is not configured"));
                }
                out.put(field, secrets.encrypt(s));
            }
        }
        return out;
    }

    private Map<String, Object> decryptSecrets(String typeId, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>(config);
        for (String field : types.secretFields(typeId)) {
            if (out.get(field) instanceof String s) {
                out.put(field, secrets.decrypt(s));
            }
        }
        return out;
    }

    public Collection<ConnectionRuntime> runtimes() {
        return runtimes.values();
    }

    public Optional<ConnectionRuntime> runtime(UUID id) {
        return Optional.ofNullable(runtimes.get(id));
    }

    /** Aging override of a connection (PIC-004), or null for the default. */
    public InstanceSettings.Aging agingFor(String connectionId) {
        try {
            ConnectionRuntime r = runtimes.get(UUID.fromString(connectionId));
            return r == null ? null : r.definition().aging();
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private void validate(ConnectionDefinition d) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (d.name() == null || d.name().isBlank() || d.name().length() > 64) {
            errors.put("name", "must be 1-64 characters");
        }
        if (d.direction() == null) {
            errors.put("direction", "must be IN, OUT or BOTH");
        }
        if (d.aging() != null && !d.aging().isConsistent()) {
            errors.put("aging", "staleAfter must be > 0 and shorter than deleteAfter (max 7 days)");
        }
        ConnectionType type = types.get(d.type()).orElse(null);
        if (type == null) {
            errors.put("type", "unknown connection type");
        } else {
            if (d.direction() != null && !type.directions().contains(d.direction())) {
                errors.put("direction", "not supported by this type");
            }
            try {
                errors.putAll(type.validate(d.config() == null ? Map.of() : d.config()));
            } catch (RuntimeException e) {
                errors.put("config", "could not be validated: " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidConnectionException(errors);
        }
    }

    @Transactional
    public ConnectionDefinition create(ConnectionDefinition input, String actor) {
        ConnectionDefinition d = new ConnectionDefinition(
                UUID.randomUUID(),
                input.name(),
                input.type(),
                input.enabled(),
                input.direction(),
                input.config(),
                input.aging());
        validate(d);
        // Secrets never reach the database in plaintext (ARCHITECTURE §10).
        d = withConfig(d, encryptSecrets(d.type(), d.config()));
        jdbc.sql("""
                INSERT INTO connection (id, name, type, enabled, direction, config, aging, updated_by)
                VALUES (:id, :name, :type, :enabled, :direction, CAST(:config AS jsonb), CAST(:aging AS jsonb), :actor)
                """)
                .param("id", d.id())
                .param("name", d.name())
                .param("type", d.type())
                .param("enabled", d.enabled())
                .param("direction", d.direction().name())
                .param("config", mapper.writeValueAsString(d.config()))
                .param("aging", d.aging() == null ? null : mapper.writeValueAsString(d.aging()))
                .param("actor", actor)
                .update();
        auditLog.record("connection.create", "connection", d.id().toString(), null, masked(d));
        ConnectionRuntime r = runtimeFor(d);
        if (r != null) {
            runtimes.put(d.id(), r);
            if (d.enabled()) {
                r.start();
            }
        }
        return d;
    }

    /**
     * Replaces a definition. Secret fields sent as the mask ({@value #SECRET_MASK}) or omitted keep
     * their stored value, so the form can be saved without re-entering passwords.
     */
    @Transactional
    public ConnectionDefinition update(UUID id, ConnectionDefinition input, String actor) {
        ConnectionRuntime existing = runtimes.get(id);
        ConnectionDefinition before = existing == null ? find(id) : existing.definition();
        if (before == null) {
            throw new UnknownConnectionException();
        }
        Map<String, Object> config = new LinkedHashMap<>(input.config() == null ? Map.of() : input.config());
        for (String secret : types.secretFields(input.type())) {
            Object v = config.get(secret);
            if ((v == null || SECRET_MASK.equals(v)) && before.config().containsKey(secret)) {
                config.put(secret, before.config().get(secret));
            }
        }
        // Stored secrets may be encrypted (from the DB) or plain (from the runtime): normalise to plain
        // for validation, then encrypt for storage.
        ConnectionDefinition d = new ConnectionDefinition(
                id,
                input.name(),
                input.type(),
                input.enabled(),
                input.direction(),
                decryptSecrets(input.type(), config),
                input.aging());
        validate(d);
        d = withConfig(d, encryptSecrets(d.type(), d.config()));
        jdbc.sql("""
                UPDATE connection SET name = :name, type = :type, enabled = :enabled, direction = :direction,
                    config = CAST(:config AS jsonb), aging = CAST(:aging AS jsonb), updated_at = now(), updated_by = :actor
                WHERE id = :id
                """)
                .param("id", id)
                .param("name", d.name())
                .param("type", d.type())
                .param("enabled", d.enabled())
                .param("direction", d.direction().name())
                .param("config", mapper.writeValueAsString(d.config()))
                .param("aging", d.aging() == null ? null : mapper.writeValueAsString(d.aging()))
                .param("actor", actor)
                .update();
        auditLog.record("connection.update", "connection", id.toString(), masked(before), masked(d));
        if (existing != null) {
            existing.stop();
        }
        ConnectionRuntime r = runtimeFor(d);
        if (r != null) {
            runtimes.put(id, r);
            if (d.enabled()) {
                r.start();
            }
        } else {
            runtimes.remove(id);
        }
        return d;
    }

    @Transactional
    public void delete(UUID id, String actor) {
        ConnectionDefinition before =
                runtimes.containsKey(id) ? runtimes.get(id).definition() : find(id);
        if (before == null) {
            throw new UnknownConnectionException();
        }
        jdbc.sql("DELETE FROM connection WHERE id = :id").param("id", id).update();
        auditLog.record("connection.delete", "connection", id.toString(), masked(before), null);
        ConnectionRuntime r = runtimes.remove(id);
        if (r != null) {
            r.stop();
        }
    }

    @Transactional
    public ConnectionDefinition setEnabled(UUID id, boolean enabled, String actor) {
        ConnectionDefinition d = runtimes.containsKey(id) ? runtimes.get(id).definition() : find(id);
        if (d == null) {
            throw new UnknownConnectionException();
        }
        return update(
                id,
                new ConnectionDefinition(id, d.name(), d.type(), enabled, d.direction(), d.config(), d.aging()),
                actor);
    }

    private ConnectionDefinition find(UUID id) {
        return loadAll().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
    }

    /** The definition with secret fields replaced by the mask - the only form ever returned or audited. */
    public ConnectionDefinition masked(ConnectionDefinition d) {
        Map<String, Object> config = new LinkedHashMap<>(d.config());
        for (String secret : types.secretFields(d.type())) {
            if (config.containsKey(secret)) {
                config.put(secret, SECRET_MASK);
            }
        }
        return new ConnectionDefinition(d.id(), d.name(), d.type(), d.enabled(), d.direction(), config, d.aging());
    }

    /** Result of a connection test (CON-001 "can be tested before saving"). */
    public record TestResult(
            boolean reachedUp, ConnectionState finalState, String detail, int framesReceived, List<String> sample) {}

    /**
     * Runs a temporary instance for up to {@code seconds} without touching the live picture: reports
     * whether it came up and the first few frames received.
     */
    public TestResult test(String typeId, Map<String, Object> config, int seconds) {
        ConnectionType type = types.get(typeId)
                .orElseThrow(() -> new InvalidConnectionException(Map.of("type", "unknown connection type")));
        Map<String, String> errors = type.validate(config);
        if (!errors.isEmpty()) {
            throw new InvalidConnectionException(errors);
        }
        AtomicReference<ConnectionState> last = new AtomicReference<>(ConnectionState.CONNECTING);
        AtomicReference<String> detail = new AtomicReference<>("");
        AtomicInteger frames = new AtomicInteger();
        List<String> sample = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch up = new CountDownLatch(1);
        boolean[] reachedUp = {false};
        ConnectionContext ctx = new ConnectionContext() {
            @Override
            public String connectionId() {
                return "test";
            }

            @Override
            public void received(String frame, Map<String, String> meta) {
                frames.incrementAndGet();
                if (sample.size() < 5) {
                    sample.add(frame.length() > 200 ? frame.substring(0, 200) + "..." : frame);
                }
            }

            @Override
            public void state(ConnectionState state, String d) {
                last.set(state);
                detail.set(d);
                if (state == ConnectionState.UP || state == ConnectionState.DEGRADED) {
                    reachedUp[0] = true;
                    up.countDown();
                } else if (state == ConnectionState.DOWN) {
                    up.countDown();
                }
            }

            @Override
            public org.slf4j.Logger logger() {
                return log;
            }
        };
        ConnectionInstance instance = null;
        try {
            instance = type.create(config, ctx);
            instance.start();
            up.await(Math.min(10, Math.max(1, seconds)), TimeUnit.SECONDS);
            if (reachedUp[0]) {
                Thread.sleep(
                        Duration.ofSeconds(Math.min(10, Math.max(1, seconds))).toMillis() / 2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            last.set(ConnectionState.DOWN);
            detail.set(e.getMessage());
        } finally {
            if (instance != null) {
                try {
                    instance.stop();
                } catch (RuntimeException ignored) {
                    // test instance only
                }
            }
        }
        return new TestResult(reachedUp[0], last.get(), detail.get(), frames.get(), List.copyOf(sample));
    }

    @Override
    public void destroy() {
        runtimes.values().forEach(ConnectionRuntime::stop);
        scheduler.shutdownNow();
    }
}
