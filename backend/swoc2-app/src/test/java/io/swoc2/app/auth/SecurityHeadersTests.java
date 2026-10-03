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

/** GEN-015 / ARCHITECTURE §17 security headers on a real server response. */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "swoc2.oidc-issuer-uri=https://auth.example.org:8443/realms/swoc2")
class SecurityHeadersTests {

    @LocalServerPort
    private int port;

    @Test
    void strictHeadersOnEveryResponse() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/config.json"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        String csp = r.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp)
                .contains("default-src 'self'", "script-src 'self'", "connect-src 'self'", "frame-ancestors 'none'")
                .contains("form-action 'self' https://auth.example.org:8443")
                .doesNotContain("unsafe-eval");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(r.headers().firstValue("Referrer-Policy")).hasValue("same-origin");
        // Plain HTTP: no HSTS (GEN-003), it would lock HTTP-only setups out.
        assertThat(r.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    @Test
    void issuerOriginParsing() {
        assertThat(ContentSecurityPolicy.origin("http://localhost:5081/realms/swoc2-dev"))
                .isEqualTo("http://localhost:5081");
        assertThat(ContentSecurityPolicy.origin("https://example.org/auth/realms/x"))
                .isEqualTo("https://example.org");
        assertThat(ContentSecurityPolicy.origin("")).isNull();
        assertThat(ContentSecurityPolicy.origin("javascript:alert(1)")).isNull();
        assertThat(ContentSecurityPolicy.origin("not a uri")).isNull();
    }
}
