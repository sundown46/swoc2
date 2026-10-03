package io.swoc2.app.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * BFF login against Keycloak (ADR 0004): authorization-code OIDC login, session cookie only,
 * no tokens in the browser. Roles are Keycloak client roles of the {@code swoc2} client
 * (REQUIREMENTS AUTH-002), mapped onto Spring authorities {@code ROLE_VIEWER/OPERATOR/
 * COMMANDER/ADMIN} with a hierarchy so {@code hasRole('VIEWER')} also passes for the higher
 * roles.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Reachable without login. The diagnostics page (GEN-010) must load on a machine where login
     * itself may be what is broken, so it, its probe endpoints and the static build assets it
     * needs are public. The assets are the same hashed bundles every user downloads anyway and
     * contain no secrets or per-user data.
     */
    private static final String[] PUBLIC_PATHS = {
        "/config.json",
        "/actuator/health",
        "/actuator/health/**",
        "/diag",
        "/diag.html",
        "/assets/**",
        "/favicon.ico",
        "/api/diag/**",
        // Spring Boot's error page: without this, an error on a public endpoint (e.g. a 400 on a
        // malformed probe parameter) is re-dispatched to /error and answered with a login redirect.
        "/error",
    };

    private final String registrationId;

    SecurityConfig(OAuth2ClientProperties oAuth2ClientProperties) {
        // Single registration in this app ("swoc2") - read the id instead of hardcoding it twice.
        this.registrationId = oAuth2ClientProperties.getRegistration().keySet().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No OAuth2 client registration configured"));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // SPA-style CSRF (ARCHITECTURE §7 "CSRF protection applies to state-changing
                // requests"): token in the readable XSRF-TOKEN cookie, echoed by the SPA in the
                // X-XSRF-TOKEN header. Needed now for POST /rt/session and /rt/send.
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .oauth2Login(
                        oauth2 -> oauth2.userInfoEndpoint(info -> info.oidcUserService(roleMappingOidcUserService())))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(authenticationEntryPoint(clientRegistrationRepository)));
        return http.build();
    }

    /**
     * Explicit, order-independent entry-point selection (deliberately not
     * {@code defaultAuthenticationEntryPointFor}, whose "first one registered becomes the
     * catch-all default" behaviour depends on configurer ordering and bit us during testing: a
     * plain {@code curl} request with no {@code Accept} header fell through to the API's
     * problem+json handler even for {@code /}). {@code /api/**} always gets problem+json
     * (CLAUDE.md "Errors"); everything else redirects to the OIDC login, matching what a
     * browser navigating to any page needs - <strong>unless</strong> resolving the
     * registration itself fails (misconfigured or Keycloak unreachable,
     * {@link LazyClientRegistrationConfig}), in which case redirecting anyway sent the browser
     * into an infinite loop against {@code /oauth2/authorization/swoc2} (a real incident: that
     * filter swallows the resolution failure and the request falls through to this same entry
     * point again). Checked once per unauthenticated browser request instead, so it fails as a
     * single readable page, never a stack trace or a loop.
     */
    private AuthenticationEntryPoint authenticationEntryPoint(
            ClientRegistrationRepository clientRegistrationRepository) {
        AuthenticationEntryPoint apiEntryPoint = (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                  {"type":"https://swoc2.example/problems/unauthenticated","title":"Unauthenticated","status":401}""");
        };
        AuthenticationEntryPoint browserLoginEntryPoint =
                new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/" + registrationId);
        AuthenticationEntryPoint guardedBrowserEntryPoint = (request, response, authException) -> {
            try {
                clientRegistrationRepository.findByRegistrationId(registrationId);
            } catch (RuntimeException resolutionFailure) {
                log.error(
                        "OIDC login unavailable: could not resolve client registration '{}' (misconfigured, or"
                                + " Keycloak unreachable)",
                        registrationId,
                        resolutionFailure);
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                response.setContentType(MediaType.TEXT_HTML_VALUE);
                response.getWriter().write("""
                                <!doctype html><title>SWOC2 - login unavailable</title>
                                <p>Login is not available right now. Ask your administrator to check the
                                SWOC2_OIDC_* configuration and that Keycloak is reachable.</p>""");
                return;
            }
            browserLoginEntryPoint.commence(request, response, authException);
        };
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> entryPoints = new LinkedHashMap<>();
        entryPoints.put(PathPatternRequestMatcher.pathPattern("/api/**"), apiEntryPoint);
        // Realtime endpoints are called by scripts (fetch/EventSource/WebSocket), never navigated to.
        entryPoints.put(PathPatternRequestMatcher.pathPattern("/rt/**"), apiEntryPoint);
        var delegate = new DelegatingAuthenticationEntryPoint(entryPoints);
        delegate.setDefaultEntryPoint(guardedBrowserEntryPoint);
        return delegate;
    }

    /**
     * Keycloak puts client roles under {@code resource_access.<clientId>.roles} (not a
     * standard OIDC claim Spring maps automatically). By default Keycloak's built-in "roles"
     * client scope only adds this claim to the <em>access</em> token, not the ID token or the
     * userinfo response (confirmed by decoding both during manual testing against a real dev
     * realm) - so it has to be read from there, not from {@code oidcUser}'s own claims. Also
     * checks the ID token/userinfo claims first in case a realm's "roles" scope mapper has been
     * reconfigured to include it there too.
     */
    private OAuth2UserService<OidcUserRequest, OidcUser> roleMappingOidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser oidcUser = delegate.loadUser(request);
            Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
            addClientRoleAuthorities(authorities, oidcUser.getClaimAsMap("resource_access"));
            addClientRoleAuthorities(authorities, resourceAccessFromAccessToken(request));
            return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resourceAccessFromAccessToken(OidcUserRequest request) {
        try {
            JWTClaimsSet claims =
                    JWTParser.parse(request.getAccessToken().getTokenValue()).getJWTClaimsSet();
            return (Map<String, Object>) claims.getClaim("resource_access");
        } catch (ParseException e) {
            // Not a JWT access token (e.g. an opaque token from a non-Keycloak provider) - no
            // client-role claim to read from it; ID token/userinfo claims are still checked.
            return null;
        }
    }

    private void addClientRoleAuthorities(Set<GrantedAuthority> authorities, Map<String, Object> resourceAccess) {
        if (resourceAccess != null
                && resourceAccess.get(registrationId) instanceof Map<?, ?> clientAccess
                && clientAccess.get("roles") instanceof Collection<?> roles) {
            roles.forEach(role -> authorities.add(
                    new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase())));
        }
    }

    /** Logout also ends the Keycloak session (ARCHITECTURE §17), not just the local one. */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clients) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(clients);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    /**
     * admin &gt; commander &gt; operator &gt; viewer (REQUIREMENTS AUTH-002): a higher role
     * automatically satisfies a {@code @PreAuthorize} check written for a lower one.
     */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
        ROLE_ADMIN > ROLE_COMMANDER
        ROLE_COMMANDER > ROLE_OPERATOR
        ROLE_OPERATOR > ROLE_VIEWER
        """);
    }
}
