package io.swoc2.app.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Regression test for a real incident, in two parts: the app refused to boot at all - not even
 * {@code /config.json} or {@code /actuator/health} - when Keycloak was unreachable or simply
 * not configured yet, because Spring Boot's auto-configured {@code ClientRegistrationRepository}
 * performs OIDC discovery (a live HTTP call to the issuer) eagerly during context startup; and
 * once that was fixed on its own, a browser hitting {@code /} in the same situation got stuck
 * in an infinite redirect loop against {@code /oauth2/authorization/swoc2} instead of a
 * readable error (Spring's own {@code OAuth2AuthorizationRequestRedirectFilter} swallows the
 * resolution failure and falls through to the same unauthenticated entry point again). This
 * points the issuer at a port nothing listens on and checks both are actually fixed.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.security.oauth2.client.provider.swoc2.issuer-uri=http://127.0.0.1:1/realms/unreachable",
            "spring.security.oauth2.client.provider.swoc2.authorization-uri=",
            "spring.security.oauth2.client.provider.swoc2.token-uri=",
            "spring.security.oauth2.client.provider.swoc2.user-info-uri=",
            "spring.security.oauth2.client.provider.swoc2.jwk-set-uri=",
        })
class OAuth2ClientBootResilienceTests {

    @LocalServerPort
    private int port;

    @Test
    void rootPathFailsCleanlyInsteadOfCrashingOrRedirectLooping() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("not available").doesNotContain("Exception", "\tat ");
    }
}
