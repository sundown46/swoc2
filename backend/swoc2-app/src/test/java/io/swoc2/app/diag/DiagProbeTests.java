package io.swoc2.app.diag;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The diagnostics probes (GEN-010) must work <em>anonymously</em> - that is their whole point -
 * and must stay bounded. Runs against a real embedded server so the WebSocket upgrade, async
 * long-poll and SSE streaming are exercised end to end, not mocked.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DiagProbeTests {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pingIsAnonymousAndReportsWhatTheServerSaw() throws Exception {
        HttpResponse<String> response = get("/api/diag/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"scheme\":\"http\"", "\"secure\":false");
    }

    @Test
    void longPollHoldsAndIsCapped() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = get("/api/diag/poll?holdMs=600000");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"heldMs\":" + DiagProbeController.MAX_POLL_HOLD.toMillis());
        assertThat(elapsedMs)
                .isGreaterThanOrEqualTo(DiagProbeController.MAX_POLL_HOLD.toMillis() - 100)
                .isLessThan(DiagProbeController.MAX_POLL_HOLD.toMillis() + 3_000);
    }

    @Test
    void longPollRejectsGarbageWithoutRedirectingToLogin() throws Exception {
        HttpResponse<String> response = get("/api/diag/poll?holdMs=notanumber");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("\tat ");
    }

    @Test
    void sseSendsAllProbeEventsThenCompletes() throws Exception {
        HttpResponse<String> response = get("/api/diag/sse");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.body().split("event:probe", -1)).hasSize(DiagProbeController.SSE_EVENT_COUNT + 1);
    }

    @Test
    void diagPageRouteIsPublic() throws Exception {
        // diag.html itself only exists in a packaged build (the SPA is copied in by the Docker
        // build), so here only "not redirected to login" is checked.
        HttpResponse<String> response = get("/diag");

        assertThat(response.statusCode()).isNotIn(301, 302, 303, 307, 401);
    }

    @Test
    void protectedApiStillRequiresLogin() throws Exception {
        assertThat(get("/api/test/whoami").statusCode()).isEqualTo(401);
    }

    @Test
    void webSocketEchoesAndEnforcesMessageLimit() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> closed = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                received.add(data.toString());
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closed.complete(statusCode);
                return null;
            }
        };
        WebSocket ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost:" + port)
                .buildAsync(URI.create("ws://localhost:" + port + DiagWebConfig.WS_PROBE_PATH), listener)
                .get(5, TimeUnit.SECONDS);

        for (int i = 0; i <= DiagEchoWebSocketHandler.MAX_MESSAGES; i++) {
            ws.sendText("ping-" + i, true).get(5, TimeUnit.SECONDS);
        }

        assertThat(closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008); // policy violation
        assertThat(received)
                .hasSize(DiagEchoWebSocketHandler.MAX_MESSAGES)
                .first()
                .isEqualTo("ping-0");
    }

    @Test
    void webSocketRejectsForeignOrigin() {
        CompletableFuture<WebSocket> attempt = client.newWebSocketBuilder()
                .header("Origin", "https://evil.example")
                .buildAsync(
                        URI.create("ws://localhost:" + port + DiagWebConfig.WS_PROBE_PATH),
                        new WebSocket.Listener() {});

        assertThat(attempt).failsWithin(Duration.ofSeconds(5));
    }
}
