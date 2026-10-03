package io.swoc2.app.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * All three transports end to end against a real embedded server (WebSocket upgrade, SSE streaming,
 * held long-polls are not reproducible with MockMvc). A test-only security chain for {@code /rt/**}
 * authenticates the user named in the {@code X-Test-User} header, so the tests don't need Keycloak;
 * the production chain (login, CSRF, ownership) is covered by {@link RealtimeHttpTests}.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "swoc2.realtime.poll-hold=800ms",
            "swoc2.realtime.heartbeat=400ms",
            "swoc2.realtime.batch-interval=200ms",
            "swoc2.realtime.demo-contacts=20"
        })
class RealtimeTransportIntegrationTests {

    @TestConfiguration
    static class TestUserSecurity {
        @Bean
        @Order(0)
        SecurityFilterChain testRealtimeChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/rt/**")
                    .csrf(c -> c.disable())
                    .addFilterBefore(
                            new OncePerRequestFilter() {
                                @Override
                                protected boolean shouldNotFilterAsyncDispatch() {
                                    return false; // long-poll answers on an async dispatch
                                }

                                @Override
                                protected void doFilterInternal(
                                        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                        throws ServletException, IOException {
                                    String user = request.getHeader("X-Test-User");
                                    if (user != null) {
                                        var auth = new TestingAuthenticationToken(user, "n/a", "ROLE_VIEWER");
                                        SecurityContextHolder.getContext().setAuthentication(auth);
                                    }
                                    chain.doFilter(request, response);
                                }
                            },
                            AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(a -> a.anyRequest().authenticated());
            return http.build();
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String createSession() throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(uri("/rt/session"))
                        .header("X-Test-User", "alice")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(201);
        return mapper.readTree(r.body()).path("sessionId").asString();
    }

    private void send(String session, String json) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(uri("/rt/send?session=" + session))
                        .header("X-Test-User", "alice")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(202);
    }

    private JsonNode poll(String session, long after) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(uri("/rt/poll?session=" + session + "&after=" + after))
                        .header("X-Test-User", "alice")
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return mapper.readTree(r.body());
    }

    @Test
    void webSocketDeliversHelloSnapshotDeltasAndHeartbeatsInSeqOrder() throws Exception {
        String session = createSession();
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder()
                .header("X-Test-User", "alice")
                .header("Origin", "http://localhost:" + port)
                .buildAsync(
                        URI.create("ws://localhost:" + port + "/rt/ws?session=" + session + "&after=0"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                received.add(mapper.readTree(data.toString()));
                                w.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        assertThat(received.poll(5, TimeUnit.SECONDS).path("type").asString()).isEqualTo("hello");
        ws.sendText("{\"v\":1,\"type\":\"subscribe\",\"payload\":{\"topic\":\"demo\"}}", true);

        List<JsonNode> messages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode m = received.poll(200, TimeUnit.MILLISECONDS);
            if (m != null) {
                messages.add(m);
            }
        }
        ws.abort();

        assertThat(messages.getFirst().path("type").asString()).isEqualTo("snapshot");
        assertThat(messages).anyMatch(m -> m.path("type").asString().equals("delta"));
        long last = 1;
        for (JsonNode m : messages) {
            if (!m.path("type").asString().equals("heartbeat")) {
                assertThat(m.path("seq").asLong()).isEqualTo(last + 1);
                last = m.path("seq").asLong();
            }
        }
    }

    /** The SPA needs the readable XSRF-TOKEN cookie before its first POST (CsrfCookieFilter). */
    @Test
    void xsrfCookieIsIssuedOnAnyPage() throws Exception {
        HttpResponse<String> r =
                http.send(HttpRequest.newBuilder(uri("/config.json")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.headers().allValues("Set-Cookie"))
                .anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN=").doesNotContainIgnoringCase("HttpOnly"));
    }

    @Test
    void webSocketForUnknownSessionIsClosed4404() throws Exception {
        CompletableFuture<Integer> closed = new CompletableFuture<>();
        http.newWebSocketBuilder()
                .header("X-Test-User", "alice")
                .header("Origin", "http://localhost:" + port)
                .buildAsync(URI.create("ws://localhost:" + port + "/rt/ws?session=nope"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                        closed.complete(code);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(closed.get(5, TimeUnit.SECONDS)).isEqualTo(4404);
    }

    @Test
    void sseStreamsWithEventIdsAndHeartbeats() throws Exception {
        String session = createSession();
        HttpResponse<java.io.InputStream> r = http.send(
                HttpRequest.newBuilder(uri("/rt/sse?session=" + session + "&after=0"))
                        .header("X-Test-User", "alice")
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        send(session, "{\"v\":1,\"type\":\"subscribe\",\"payload\":{\"topic\":\"demo\"}}");

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            long deadline = System.currentTimeMillis() + 2500;
            String line;
            while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.stream().filter(l -> l.contains("\"heartbeat\"")).count() >= 1
                        && lines.stream().anyMatch(l -> l.contains("\"delta\""))) {
                    break;
                }
            }
        }

        assertThat(lines).contains("id:1");
        assertThat(lines).anyMatch(l -> l.startsWith("data:") && l.contains("\"snapshot\""));
        assertThat(lines).anyMatch(l -> l.startsWith("data:") && l.contains("\"delta\""));
    }

    @Test
    void longPollHoldsThenAnswersEmptyAndResumesWithoutLoss() throws Exception {
        String session = createSession();
        assertThat(poll(session, 0).get(0).path("type").asString()).isEqualTo("hello");

        long start = System.nanoTime();
        JsonNode idle = poll(session, 1);
        long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(idle).isEmpty();
        assertThat(heldMs).isBetween(700L, 3000L);

        send(session, "{\"v\":1,\"type\":\"subscribe\",\"payload\":{\"topic\":\"demo\"}}");
        Thread.sleep(700); // deltas accumulate in the buffer while no poll is attached
        JsonNode batch = poll(session, 1);
        assertThat(batch.get(0).path("type").asString()).isEqualTo("snapshot");
        long expected = 2;
        for (JsonNode m : batch) {
            assertThat(m.path("seq").asLong()).isEqualTo(expected++);
        }
    }

    @Test
    void switchingFromLongPollToWebSocketReplaysTheGap() throws Exception {
        String session = createSession();
        poll(session, 0); // hello = 1
        send(session, "{\"v\":1,\"type\":\"subscribe\",\"payload\":{\"topic\":\"demo\"}}");
        Thread.sleep(500);

        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder()
                .header("X-Test-User", "alice")
                .header("Origin", "http://localhost:" + port)
                .buildAsync(
                        URI.create("ws://localhost:" + port + "/rt/ws?session=" + session + "&after=1"),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                                received.add(mapper.readTree(data.toString()));
                                w.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        JsonNode first = received.poll(5, TimeUnit.SECONDS);
        ws.abort();
        assertThat(first.path("seq").asLong()).isEqualTo(2);
        assertThat(first.path("type").asString()).isEqualTo("snapshot");
    }
}
