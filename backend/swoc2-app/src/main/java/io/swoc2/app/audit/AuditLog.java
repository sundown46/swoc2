package io.swoc2.app.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Records audit events. The actor and client IP are taken from the current request automatically;
 * outside a request (startup, scheduled jobs) the actor is {@code system}. If the record cannot be
 * written the call throws: an action that cannot be audited must not silently succeed (run it in
 * the same transaction as the change to make that atomic).
 */
@Service
public class AuditLog {

    /** Upper bound per JSON column, so a huge before/after payload cannot bloat the log. */
    private static final int MAX_JSON_CHARS = 64 * 1024;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    AuditLog(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Records a change of {@code target} by the current user. */
    public void record(String action, String targetType, String targetId, Object before, Object after) {
        record(action, targetType, targetId, before, after, null);
    }

    public void record(
            String action, String targetType, String targetId, Object before, Object after, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO audit_event
                    (actor, action, target_type, target_id, before_state, after_state, details, client_ip)
                VALUES (:actor, :action, :targetType, :targetId,
                        CAST(:before AS jsonb), CAST(:after AS jsonb), CAST(:details AS jsonb), :ip)
                """)
                .param("actor", currentActor())
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("before", json(before))
                .param("after", json(after))
                .param("details", json(details))
                .param("ip", clientIp())
                .update();
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode node = mapper.valueToTree(value);
        String text = mapper.writeValueAsString(node);
        if (text.length() > MAX_JSON_CHARS) {
            return mapper.writeValueAsString(Map.of("truncated", true, "size", text.length()));
        }
        return text;
    }

    /** {@code preferred_username} for OIDC users (readable), else the principal name. */
    static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        if (auth.getPrincipal() instanceof OidcUser oidc && oidc.getPreferredUsername() != null) {
            return oidc.getPreferredUsername();
        }
        return auth.getName();
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            // ForwardedHeaderFilter has already applied X-Forwarded-For when forwarded headers are trusted.
            return request.getRemoteAddr();
        }
        return null;
    }
}
