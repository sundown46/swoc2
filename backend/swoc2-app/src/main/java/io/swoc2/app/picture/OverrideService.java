package io.swoc2.app.picture;

import io.swoc2.app.audit.AuditLog;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.ContactOverride;
import io.swoc2.domain.picture.SourceKey;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists operator overrides (PIC-003, ARCHITECTURE §5.2) and keeps the picture store in sync.
 * Every change is audited with before/after (AUTH-005) in the same transaction as the write.
 */
@Service
public class OverrideService {

    private static final Logger log = LoggerFactory.getLogger(OverrideService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditLog auditLog;
    private final PictureStore store;

    OverrideService(JdbcClient jdbc, ObjectMapper mapper, AuditLog auditLog, PictureStore store) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.auditLog = auditLog;
        this.store = store;
        store.loadOverrides(loadAll());
    }

    private Map<SourceKey, ContactOverride> loadAll() {
        Map<SourceKey, ContactOverride> result = new HashMap<>();
        jdbc.sql(
                        "SELECT connection_id, source_system_id, source_track_id, fields::text AS fields FROM picture_override")
                .query((rs, n) -> {
                    try {
                        result.put(
                                new SourceKey(rs.getString(1), rs.getString(2), rs.getString(3)),
                                mapper.readValue(rs.getString("fields"), ContactOverride.class));
                    } catch (JacksonException | IllegalArgumentException unreadable) {
                        // One broken row must not keep the picture from starting.
                        log.error(
                                "Skipping unreadable override for {}/{}/{}",
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                unreadable);
                    }
                    return null;
                })
                .list();
        log.info("Loaded {} contact overrides", result.size());
        return result;
    }

    /** Sets the override of a contact (fields that are null are not overridden). */
    @Transactional
    public Contact set(Contact contact, ContactOverride override, String actor) {
        SourceKey key = contact.key();
        ContactOverride before = contact.overridden();
        if (override.isEmpty()) {
            return reset(contact);
        }
        jdbc.sql("""
                INSERT INTO picture_override (connection_id, source_system_id, source_track_id, fields, updated_at, updated_by)
                VALUES (:c, :s, :t, CAST(:f AS jsonb), now(), :actor)
                ON CONFLICT (connection_id, source_system_id, source_track_id)
                DO UPDATE SET fields = EXCLUDED.fields, updated_at = now(), updated_by = :actor
                """)
                .param("c", key.connectionId())
                .param("s", key.sourceSystemId())
                .param("t", key.sourceTrackId())
                .param("f", mapper.writeValueAsString(override))
                .param("actor", actor)
                .update();
        auditLog.record("contact.override", "contact", keyText(key), before.isEmpty() ? null : before, override);
        store.setOverride(key, override);
        return store.get(key).orElse(contact);
    }

    /** Removes every override of a contact; source values show again. */
    @Transactional
    public Contact reset(Contact contact) {
        SourceKey key = contact.key();
        int deleted = jdbc.sql("""
                DELETE FROM picture_override
                WHERE connection_id = :c AND source_system_id = :s AND source_track_id = :t
                """)
                .param("c", key.connectionId())
                .param("s", key.sourceSystemId())
                .param("t", key.sourceTrackId())
                .update();
        if (deleted > 0) {
            auditLog.record("contact.override.reset", "contact", keyText(key), contact.overridden(), null);
        }
        store.setOverride(key, ContactOverride.NONE);
        return store.get(key).orElse(contact);
    }

    /** Clears all overrides (optional part of a wipe). Returns the number removed. */
    @Transactional
    int clearAll() {
        int deleted = jdbc.sql("DELETE FROM picture_override").update();
        store.clearOverrides();
        return deleted;
    }

    static String keyText(SourceKey key) {
        return key.connectionId() + "/" + key.sourceSystemId() + "/" + key.sourceTrackId();
    }
}
