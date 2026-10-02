package io.swoc2.app.auth;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.bind.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-protected test endpoints (ROADMAP P0 item 5), used by {@code docs/TESTING.md} §1.2 to
 * verify the Keycloak-&gt;Spring role mapping and the role hierarchy (AUTH-002) actually work,
 * not just that login succeeds.
 */
@RestController
@RequestMapping("/api/test")
class RoleTestController {

    /** Any authenticated user, any role: proves login + role mapping landed on the principal. */
    @GetMapping("/whoami")
    Map<String, Object> whoami(@AuthenticationPrincipal OidcUser user) {
        return Map.of(
                "username", user.getPreferredUsername(),
                "authorities",
                        user.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .toList());
    }

    /**
     * Requires VIEWER or anything the role hierarchy ranks above it. Expected to succeed for all
     * four test users (viewer1/operator1/commander1/admin1).
     */
    @GetMapping("/viewer-or-higher")
    @PreAuthorize("hasRole('VIEWER')")
    Map<String, Object> viewerOrHigher() {
        return Map.of("ok", true, "requiredRole", "VIEWER");
    }

    /**
     * Requires ADMIN specifically. Expected to succeed only for admin1 and return 403 for the
     * other three test users - the actual "role-protected endpoint rejects the wrong role" check.
     */
    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, Object> adminOnly() {
        return Map.of("ok", true, "requiredRole", "ADMIN");
    }
}
