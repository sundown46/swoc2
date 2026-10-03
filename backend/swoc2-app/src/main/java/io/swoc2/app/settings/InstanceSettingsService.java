package io.swoc2.app.settings;

import io.swoc2.app.audit.AuditLog;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes {@link InstanceSettings}. Reads are served from memory (settings are read on
 * hot paths, e.g. the SEDAP router); writes go to the database in the same transaction as their
 * audit record. A stored document that no longer parses (e.g. after a schema change) falls back to
 * the defaults with an error log instead of breaking startup.
 */
@Service
public class InstanceSettingsService {

    private static final Logger log = LoggerFactory.getLogger(InstanceSettingsService.class);
    static final String KEY = "instance";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditLog auditLog;
    private volatile InstanceSettings current;

    InstanceSettingsService(JdbcClient jdbc, ObjectMapper mapper, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auditLog = auditLog;
        this.current = load();
    }

    /** Current settings (never null). */
    public InstanceSettings current() {
        return current;
    }

    @Transactional
    public InstanceSettings update(InstanceSettings next, String actor) {
        InstanceSettings before = current;
        jdbc.sql("""
                INSERT INTO instance_setting (key, value, updated_at, updated_by)
                VALUES (:key, CAST(:value AS jsonb), now(), :actor)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = :actor
                """)
                .param("key", KEY)
                .param("value", mapper.writeValueAsString(next))
                .param("actor", actor)
                .update();
        auditLog.record("settings.update", "instance-settings", KEY, before, next);
        current = next;
        return next;
    }

    private InstanceSettings load() {
        Optional<String> stored = jdbc.sql("SELECT value::text FROM instance_setting WHERE key = :key")
                .param("key", KEY)
                .query(String.class)
                .optional();
        if (stored.isEmpty()) {
            return InstanceSettings.defaults();
        }
        try {
            return mapper.readValue(stored.get(), InstanceSettings.class);
        } catch (JacksonException unreadable) {
            log.error(
                    "Stored instance settings are unreadable; using defaults until an admin saves new ones",
                    unreadable);
            return InstanceSettings.defaults();
        }
    }
}
