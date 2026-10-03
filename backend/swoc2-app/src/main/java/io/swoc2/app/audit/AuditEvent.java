package io.swoc2.app.audit;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * One audit record as returned by {@code GET /api/audit}.
 *
 * @param actor user name ({@code preferred_username}) or {@code system} / {@code plugin:<id>}
 * @param action dotted verb, e.g. {@code plugin.disable}, {@code settings.update}
 * @param before state before the change (JSON), or null
 * @param after state after the change (JSON), or null
 * @param details anything else worth keeping (JSON), or null
 */
public record AuditEvent(
        long id,
        Instant occurredAt,
        String actor,
        String action,
        String targetType,
        String targetId,
        JsonNode before,
        JsonNode after,
        JsonNode details,
        String clientIp) {}
