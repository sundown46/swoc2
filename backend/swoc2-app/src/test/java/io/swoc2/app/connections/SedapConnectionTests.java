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

import io.swoc2.app.picture.PictureStore;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.ContactKind;
import io.swoc2.pluginapi.connection.ConnectionState;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * End to end over real TCP sockets: connection manager API -> transport -> SEDAP ingest -> live
 * picture, incl. loop prevention, bad input, reconnect and the server transport (SDX-002/005,
 * CON-001/002/003/005).
 */
@SpringBootTest
class SedapConnectionTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PictureStore picture;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private io.swoc2.app.settings.InstanceSettingsService settings;

    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    private static RequestPostProcessor admin() {
        return oidcLogin()
                .idToken(t -> t.claim("preferred_username", "admin1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
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

    private UUID createConnection(String body) throws Exception {
        String response = mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).path("id").asString());
    }

    @Test
    void tcpClientFeedsThePictureWithLoopPreventionAndSurvivesGarbage() throws Exception {
        try (ServerSocket sender = new ServerSocket(0)) {
            UUID id = createConnection("""
                    {"name":"tcp-client-test","type":"sedap-tcp-client","direction":"BOTH",
                     "config":{"host":"127.0.0.1","port":%d}}""".formatted(sender.getLocalPort()));
            try (Socket peer = sender.accept()) {
                ConnectionRuntime runtime = connections.runtime(id).orElseThrow();
                await("UP", () -> runtime.state() == ConnectionState.UP);
                OutputStream out = peer.getOutputStream();
                String lines = String.join(
                        "\n",
                        "OWNUNIT;01;0191C643A8AF;SHIP1;U;;;53.5;8.1;0;5;90;90;;;Ship one;SFSPCLFF-------",
                        "CONTACT;02;0191C643A8B0;SHIP1;R;;;C-1;FALSE;53.6;8.2;0;;;;12;45;;;;;;;Fishing vessel;R;SNSPXF---------;211234567;;;",
                        // relative to SHIP1's OWNUNIT: 1000 m north
                        "CONTACT;03;0191C643A8B1;SHIP1;U;;;C-2;FALSE;;;;0;1000;0;;;;;;;;;Buoy",
                        // duplicate of the previous line (same sender/type/number/time) -> dropped
                        "CONTACT;03;0191C643A8B1;SHIP1;U;;;C-2;FALSE;;;;0;1000;0;;;;;;;;;Buoy",
                        // our own sender ID -> dropped (other tests may have changed it, so read it)
                        "CONTACT;04;0191C643A8B2;" + settings.current().senderId() + ";U;;;C-3;FALSE;53;8",
                        "THIS IS NOT SEDAP",
                        "HEARTBEAT;05;0191C643A8B3;SHIP1",
                        "");
                out.write(lines.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                await(
                        "contacts",
                        () -> picture.all().stream()
                                        .filter(c -> c.key().connectionId().equals(id.toString()))
                                        .count()
                                == 3);
                // Only this connection's contacts: other tests share the same live picture.
                Contact own = picture.all().stream()
                        .filter(c -> c.kind() == ContactKind.OWNUNIT)
                        .filter(c -> c.key().connectionId().equals(id.toString()))
                        .findFirst()
                        .orElseThrow();
                assertThat(own.name()).isEqualTo("Ship one");
                Contact fishing = picture.all().stream()
                        .filter(c -> "C-1".equals(c.key().sourceTrackId()))
                        .findFirst()
                        .orElseThrow();
                assertThat(fishing.ids()).containsEntry("mmsi", "211234567");
                assertThat(fishing.sourceIndicator()).isEqualTo("R");
                assertThat(fishing.course()).isEqualTo(45.0);
                Contact buoy = picture.all().stream()
                        .filter(c -> "C-2".equals(c.key().sourceTrackId()))
                        .findFirst()
                        .orElseThrow();
                assertThat(buoy.position().latitude()).isBetween(53.508, 53.510); // ~1000 m north of 53.5

                await("heartbeat", () -> runtime.status().lastHeartbeat() != null);
                var metrics = runtime.status().metrics();
                assertThat(metrics.messagesIn()).isEqualTo(7);
                assertThat(metrics.dropped()).isEqualTo(2);
                assertThat(metrics.errors()).isEqualTo(1); // the garbage line

                // Delete flag removes the contact.
                out.write("CONTACT;06;0191C643A8B4;SHIP1;R;;;C-1;TRUE\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                await(
                        "delete",
                        () -> picture.all().stream()
                                .noneMatch(c -> "C-1".equals(c.key().sourceTrackId())));
            }
            // Peer closed: connection goes DOWN and schedules a reconnect.
            ConnectionRuntime runtime = connections.runtime(id).orElseThrow();
            await("DOWN", () -> runtime.state() == ConnectionState.DOWN);
            assertThat(runtime.status().detail()).contains("retry in");
            try (Socket again = sender.accept()) {
                await("reconnected", () -> runtime.state() == ConnectionState.UP);
                assertThat(runtime.status().metrics().reconnects()).isGreaterThanOrEqualTo(1);
            }
            mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf()))
                    .andExpect(status().isOk());
            assertThat(connections.runtime(id)).isEmpty();
        }
    }

    @Test
    void tcpServerAcceptsSenders() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        UUID id = createConnection("""
                {"name":"tcp-server-test","type":"sedap-tcp-server","direction":"IN","config":{"port":%d}}""".formatted(port));
        ConnectionRuntime runtime = connections.runtime(id).orElseThrow();
        await("listening", () -> runtime.state() == ConnectionState.DEGRADED);
        try (Socket client = new Socket("127.0.0.1", port)) {
            await("client counted", () -> runtime.state() == ConnectionState.UP);
            client.getOutputStream()
                    .write("CONTACT;;;SRV;U;;;S-1;FALSE;54;9\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            await(
                    "contact",
                    () -> picture.all().stream()
                            .anyMatch(c -> "S-1".equals(c.key().sourceTrackId())));
        }
        await("back to degraded", () -> runtime.state() == ConnectionState.DEGRADED);
        mvc.perform(post("/api/connections/" + id + "/disable").with(admin()).with(csrf()))
                .andExpect(jsonPath("$.enabled").value(false));
        await("disabled", () -> runtime.state() == ConnectionState.DISABLED);
        mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void invalidDefinitionsAreRejectedPerField() throws Exception {
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"\",\"type\":\"sedap-tcp-client\",\"config\":{\"host\":\"bad host!\",\"port\":70000}}")
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.host").exists())
                .andExpect(jsonPath("$.fieldErrors.port").exists());
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"nope\"}")
                        .with(admin())
                        .with(csrf()))
                .andExpect(jsonPath("$.fieldErrors.type").value("unknown connection type"));
    }

    @Test
    void testEndpointTriesWithoutSaving() throws Exception {
        try (ServerSocket sender = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket s = sender.accept()) {
                    s.getOutputStream().write("HEARTBEAT;01\n".getBytes(StandardCharsets.ISO_8859_1));
                    s.getOutputStream().flush();
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                    // test peer
                }
            });
            mvc.perform(post("/api/connections/test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"type\":\"sedap-tcp-client\",\"seconds\":2,\"config\":{\"host\":\"127.0.0.1\",\"port\":%d}}"
                                            .formatted(sender.getLocalPort()))
                            .with(admin())
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reachedUp").value(true))
                    .andExpect(jsonPath("$.framesReceived").value(1));
        }
        mvc.perform(post("/api/connections/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"type\":\"sedap-tcp-client\",\"seconds\":2,\"config\":{\"host\":\"127.0.0.1\",\"port\":1}}")
                        .with(admin())
                        .with(csrf()))
                .andExpect(jsonPath("$.reachedUp").value(false))
                .andExpect(jsonPath("$.finalState").value("DOWN"));
    }

    @Test
    void typesAndRolesAndAudit() throws Exception {
        mvc.perform(get("/api/connections/types").with(admin()))
                .andExpect(jsonPath("$[?(@.id=='sedap-tcp-client')].configSchema.properties.host")
                        .exists());
        RequestPostProcessor viewer = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
        mvc.perform(get("/api/connections/types").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(viewer)
                        .with(csrf()))
                .andExpect(status().isForbidden());
        UUID id = createConnection(
                "{\"name\":\"audited\",\"type\":\"sedap-tcp-client\",\"enabled\":false,\"config\":{\"host\":\"127.0.0.1\",\"port\":1}}");
        mvc.perform(get("/api/connections").with(viewer))
                .andExpect(jsonPath("$[?(@.definition.name=='audited')].definition.config.host")
                        .isEmpty());
        mvc.perform(get("/api/audit").param("action", "connection.create").with(admin()))
                .andExpect(jsonPath("$[0].after.name").value("audited"));
        mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf())).andExpect(status().isOk());
    }
}
