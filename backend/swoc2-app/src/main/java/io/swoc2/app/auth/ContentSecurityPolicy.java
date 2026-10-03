package io.swoc2.app.auth;

import java.net.URI;

/**
 * Builds the Content-Security-Policy (ARCHITECTURE §17, GEN-015). Strict by default: only our own
 * origin for scripts, data and connections (CLAUDE.md principle 4: no CDNs anyway). The few
 * relaxations are deliberate:
 *
 * <ul>
 *   <li>{@code style-src 'unsafe-inline'}: React/Mantine set inline {@code style} attributes;
 *       scripts stay strictly external
 *   <li>{@code img-src data: blob:}: the symbol atlas is handed to OpenLayers as a data URL
 *   <li>{@code worker-src blob:}: OpenLayers' WebGL layer builds its buffers in a blob worker
 *   <li>{@code form-action} includes the Keycloak origin: browsers apply form-action to the redirect
 *       after the logout form, which ends at Keycloak's end-session endpoint
 * </ul>
 */
final class ContentSecurityPolicy {

    private ContentSecurityPolicy() {}

    static String build(String oidcIssuerUri) {
        String keycloak = origin(oidcIssuerUri);
        return String.join(
                "; ",
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data: blob:",
                "font-src 'self'",
                "connect-src 'self'",
                "worker-src 'self' blob:",
                "object-src 'none'",
                "base-uri 'self'",
                "frame-ancestors 'none'",
                "form-action 'self'" + (keycloak == null ? "" : " " + keycloak));
    }

    /** Scheme://host[:port] of the issuer, or null if not configured or not a plain http(s) URL. */
    static String origin(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(uri.strip());
            String scheme = parsed.getScheme();
            if (parsed.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                return null;
            }
            return scheme + "://" + parsed.getHost() + (parsed.getPort() == -1 ? "" : ":" + parsed.getPort());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
