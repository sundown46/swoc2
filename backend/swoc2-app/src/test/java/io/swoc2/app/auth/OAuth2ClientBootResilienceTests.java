package io.swoc2.app.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression test for a real incident: the app refused to boot at all - not even
 * {@code /config.json} or {@code /actuator/health} - when Keycloak was unreachable or simply
 * not configured yet, because Spring Boot's auto-configured {@code ClientRegistrationRepository}
 * performs OIDC discovery (a live HTTP call to the issuer) eagerly during context startup.
 * {@link LazyClientRegistrationConfig} defers that call to the first real login attempt; this
 * test points the issuer at a port nothing listens on and asserts the context still loads.
 */
@SpringBootTest(
        properties = {
            "spring.security.oauth2.client.provider.swoc2.issuer-uri=http://127.0.0.1:1/realms/unreachable",
            "spring.security.oauth2.client.provider.swoc2.authorization-uri=",
            "spring.security.oauth2.client.provider.swoc2.token-uri=",
            "spring.security.oauth2.client.provider.swoc2.user-info-uri=",
            "spring.security.oauth2.client.provider.swoc2.jwk-set-uri=",
        })
class OAuth2ClientBootResilienceTests {

    @Test
    void contextLoadsEvenWhenKeycloakIsUnreachable() {}
}
