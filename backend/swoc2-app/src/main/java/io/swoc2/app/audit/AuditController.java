package io.swoc2.app.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit log query API for the audit viewer (ADM-006): filter by actor, action prefix and time,
 * newest first, keyset-paginated with {@code beforeId}.
 */
@RestController
@RequestMapping("/api/audit")
class AuditController {

    private static final int MAX_LIMIT = 500;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    AuditController(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<AuditEvent> query(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
        return jdbc.sql("""
                SELECT id, occurred_at, actor, action, target_type, target_id,
                       before_state::text AS before_state, after_state::text AS after_state,
                       details::text AS details, client_ip
                FROM audit_event
                WHERE (CAST(:actor AS text) IS NULL OR actor = :actor)
                  AND (CAST(:action AS text) IS NULL OR action LIKE :actionPrefix)
                  AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                  AND (CAST(:beforeId AS bigint) IS NULL OR id < :beforeId)
                ORDER BY id DESC
                LIMIT :limit
                """)
                .param("actor", blankToNull(actor))
                .param("action", blankToNull(action))
                .param("actionPrefix", action == null ? null : escapeLike(action.strip()) + "%")
                .param("from", from == null ? null : java.sql.Timestamp.from(from))
                .param("to", to == null ? null : java.sql.Timestamp.from(to))
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query((rs, n) -> new AuditEvent(
                        rs.getLong("id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        parse(rs.getString("before_state")),
                        parse(rs.getString("after_state")),
                        parse(rs.getString("details")),
                        rs.getString("client_ip")))
                .list();
    }

    private JsonNode parse(String json) {
        return json == null ? null : mapper.readTree(json);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
