package io.swoc2.app.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swoc2.app.connections.sedap.SedapOutbound;
import io.swoc2.app.settings.InstanceSettingsService;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.codec.SedapDecoder;
import io.swoc2.sedap.codec.SedapMessage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Outbound path (SDX-004, CON-005, CON-007) with two real TCP peers. */
@SpringBootTest
class SedapOutboundTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private SedapOutbound outbound;

    @Autowired
    private InstanceSettingsService settings;

    private final JsonMapper json = JsonMapper.builder().build();

    private static RequestPostProcessor admin() {
        return oidcLogin()
                .idToken(t -> t.claim("preferred_username", "admin1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** A SEDAP peer that SWOC2 connects to; collects every non-heartbeat line it receives. */
    static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final BlockingQueue<String> heartbeats = new LinkedBlockingQueue<>();
        volatile Socket socket;

        Peer() throws Exception {
            Thread.ofVirtual().start(() -> {
                try {
                    socket = server.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = in.readLine()) != null) {
                        (line.startsWith("HEARTBEAT") ? heartbeats : lines).add(line);
                    }
                } catch (Exception ignored) {
                    // closed
                }
            });
        }

        void write(String line) throws Exception {
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        }

        @Override
        public void close() throws Exception {
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private UUID connect(MockMvc mvc, String name, Peer peer) throws Exception {
        String body = mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"%s\",\"type\":\"sedap-tcp-client\",\"direction\":\"BOTH\",\"config\":{\"host\":\"127.0.0.1\",\"port\":%d}}"
                                        .formatted(name, peer.server.getLocalPort()))
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("id").asString());
        await(
                name + " up",
                () -> connections.runtime(id).orElseThrow().state() == ConnectionState.UP && peer.socket != null);
        return id;
    }

    @Test
    void outboundStampsHeaderNumbersPerTypeRoutesAndNeverReturnsToOrigin() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        try (Peer a = new Peer();
                Peer b = new Peer()) {
            UUID idA = connect(mvc, "out-a", a);
            UUID idB = connect(mvc, "out-b", b);

            // Admin send to all: both peers get it, with our sender, a number and a fresh time.
            mvc.perform(post("/api/sedap/send")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"TEXT;7F;0000000000AA;FAKE;U;;;;04;NONE;hello\"}")
                            .with(admin())
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deliveredTo.length()").value(2));
            String atA = a.lines.poll(5, TimeUnit.SECONDS);
            String atB = b.lines.poll(5, TimeUnit.SECONDS);
            assertThat(atA).isEqualTo(atB);
            SedapMessage m = new SedapDecoder().decode(atA).message();
            assertThat(m.header().sender()).isEqualTo(settings.current().senderId());
            assertThat(m.header().number()).isNotEqualTo(0x7F);
            assertThat(m.header().time()).isAfter(java.time.Instant.now().minusSeconds(10));

            // Numbers count per type.
            var first = outbound.send(
                    SedapMessage.builder(MessageType.TEXT).set("Text", "x").build(), new SedapOutbound.Connection(idA));
            var second = outbound.send(
                    SedapMessage.builder(MessageType.TEXT).set("Text", "y").build(), new SedapOutbound.Connection(idA));
            int n1 = new SedapDecoder().decode(first.line()).message().header().number();
            int n2 = new SedapDecoder().decode(second.line()).message().header().number();
            assertThat(n2).isEqualTo((n1 + 1) & 0x7F);
            a.lines.clear();

            // Never back to the origin connection (CON-005).
            var sent = outbound.send(
                    SedapMessage.builder(MessageType.TEXT).set("Text", "fwd").build(),
                    new SedapOutbound.AllConnections(idA.toString()));
            assertThat(sent.deliveredTo()).containsExactly("out-b");
            assertThat(a.lines.poll(500, TimeUnit.MILLISECONDS)).isNull();
            b.lines.clear();

            // OWNUNIT routing (CON-007): UNIT7 is heard on B, so commands for it go only to B.
            b.write("OWNUNIT;01;;UNIT7;U;;;53.5;8.1");
            await(
                    "route",
                    () -> outbound.send(
                                    SedapMessage.builder(MessageType.HEARTBEAT).build(),
                                    new SedapOutbound.OwnUnit("UNIT7"))
                            .deliveredTo()
                            .equals(List.of("out-b")));

            // Our heartbeat arrives at 1 Hz.
            assertThat(a.heartbeats.poll(3, TimeUnit.SECONDS))
                    .startsWith("HEARTBEAT;")
                    .contains(";" + settings.current().senderId());

            mvc.perform(get("/api/audit").param("action", "sedap.send").with(admin()))
                    .andExpect(jsonPath("$[0].details.deliveredTo.length()").value(2));
            mvc.perform(delete("/api/connections/" + idA).with(admin()).with(csrf()));
            mvc.perform(delete("/api/connections/" + idB).with(admin()).with(csrf()));
        }
    }

    @Test
    void sendRejectsInvalidMessagesAndNonAdmins() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        mvc.perform(post("/api/sedap/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"CONTACT;;;;;;;1;FALSE;95;8\"}")
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Latitude")));
        mvc.perform(post("/api/sedap/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"HEARTBEAT\"}")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
