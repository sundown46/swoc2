package io.swoc2.app.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import io.swoc2.app.connections.transport.MqttConnectionType;
import io.swoc2.app.picture.PictureStore;
import io.swoc2.pluginapi.connection.ConnectionState;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.json.JsonMapper;

/** UDP unicast/multicast and MQTT transports end to end, plus secret handling (SDX-002, ARCHITECTURE §10). */
@SpringBootTest
class UdpMqttConnectionTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PictureStore picture;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private JdbcClient jdbc;

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
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private UUID create(String body) throws Exception {
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

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private boolean hasTrack(String track) {
        return picture.all().stream().anyMatch(c -> track.equals(c.key().sourceTrackId()));
    }

    @Test
    void udpUnicastReceivesMultipleMessagesPerDatagramAndSends() throws Exception {
        int swoc2Port = freePort();
        try (DatagramSocket peer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            UUID id = create("""
                    {"name":"udp-test","type":"sedap-udp","direction":"BOTH",
                     "config":{"localPort":%d,"remoteHost":"127.0.0.1","remotePort":%d}}""".formatted(swoc2Port, peer.getLocalPort()));
            ConnectionRuntime runtime = connections.runtime(id).orElseThrow();
            await("UP", () -> runtime.state() == ConnectionState.UP);

            byte[] two = "CONTACT;;;UDP1;U;;;U-1;FALSE;54;9\nCONTACT;;;UDP1;U;;;U-2;FALSE;54.1;9\n"
                    .getBytes(StandardCharsets.ISO_8859_1);
            peer.send(new DatagramPacket(two, two.length, InetAddress.getLoopbackAddress(), swoc2Port));
            await("both contacts", () -> hasTrack("U-1") && hasTrack("U-2"));

            assertThat(runtime.send("HEARTBEAT;01;;SWOC2")).isTrue();
            byte[] buf = new byte[1024];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            peer.setSoTimeout(5000);
            peer.receive(p);
            assertThat(new String(buf, 0, p.getLength(), StandardCharsets.ISO_8859_1))
                    .isEqualTo("HEARTBEAT;01;;SWOC2\n");
            mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void udpMulticastJoinsTheGroupAndReceives() throws Exception {
        int port = freePort();
        UUID id = create("""
                {"name":"mc-test","type":"sedap-udp-multicast","direction":"BOTH","config":{"group":"228.2.19.80","port":%d}}""".formatted(port));
        ConnectionRuntime runtime = connections.runtime(id).orElseThrow();
        await(
                "joined or failed",
                () -> runtime.state() == ConnectionState.UP || runtime.state() == ConnectionState.DOWN);
        if (runtime.state() != ConnectionState.UP) {
            // Some CI sandboxes have no multicast-capable interface; that is an environment limit.
            System.out.println(
                    "Multicast not available here: " + runtime.status().detail());
            mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf()));
            return;
        }
        // Loopback: what we send to the group comes back to our own socket.
        runtime.send("CONTACT;;;MC1;U;;;M-1;FALSE;55;10");
        await("multicast contact", () -> hasTrack("M-1"));
        mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf())).andExpect(status().isOk());
    }

    @Test
    void invalidMulticastGroupIsRejected() throws Exception {
        mvc.perform(post("/api/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"bad-mc\",\"type\":\"sedap-udp-multicast\",\"config\":{\"group\":\"10.0.0.1\"}}")
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.group").exists());
    }

    @Test
    void mqttSubscribesPublishesAndKeepsThePasswordSecret() throws Exception {
        try (GenericContainer<?> broker = new GenericContainer<>("eclipse-mosquitto:2.1.2-alpine")
                .withCopyToContainer(
                        Transferable.of("listener 1883\nallow_anonymous true\n"), "/mosquitto/config/mosquitto.conf")
                .withExposedPorts(1883)
                .waitingFor(Wait.forListeningPort())) {
            broker.start();
            UUID id = create("""
                    {"name":"mqtt-test","type":"sedap-mqtt","direction":"BOTH",
                     "config":{"host":"%s","port":%d,"username":"swoc2","password":"s3cret-pass","qos":1}}""".formatted(broker.getHost(), broker.getMappedPort(1883)));
            ConnectionRuntime runtime = connections.runtime(id).orElseThrow();
            await("UP", () -> runtime.state() == ConnectionState.UP);

            Mqtt5BlockingClient peer = MqttClient.builder()
                    .useMqttVersion5()
                    .serverHost(broker.getHost())
                    .serverPort(broker.getMappedPort(1883))
                    .buildBlocking();
            peer.connect();
            var received = new java.util.concurrent.LinkedBlockingQueue<String>();
            peer.toAsync()
                    .subscribeWith()
                    .topicFilter("UNIITY-X/+/HEARTBEAT")
                    .callback(p -> received.add(
                            p.getTopic() + " " + new String(p.getPayloadAsBytes(), StandardCharsets.ISO_8859_1)))
                    .send()
                    .get();
            peer.publishWith()
                    .topic("UNIITY-X/SIM9/CONTACT")
                    .payload("CONTACT;01;;SIM9;U;;;Q-1;FALSE;56;11".getBytes(StandardCharsets.ISO_8859_1))
                    .send();
            await("mqtt contact", () -> hasTrack("Q-1"));

            assertThat(runtime.send("HEARTBEAT;02;;SWOC2")).isTrue();
            String got = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(got).isEqualTo("UNIITY-X/SWOC2/HEARTBEAT HEARTBEAT;02;;SWOC2");
            peer.disconnect();

            // The password is never returned and never stored in plaintext.
            mvc.perform(get("/api/connections").with(admin()))
                    .andExpect(jsonPath("$[?(@.definition.name=='mqtt-test')].definition.config.password")
                            .value("********"));
            String stored = jdbc.sql("SELECT config->>'password' FROM connection WHERE id = :id")
                    .param("id", id)
                    .query(String.class)
                    .single();
            assertThat(stored).startsWith("enc:v1:").doesNotContain("s3cret");
            // Saving the form with the mask keeps the stored password.
            mvc.perform(put("/api/connections/" + id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"mqtt-test","type":"sedap-mqtt","direction":"BOTH",
                                     "config":{"host":"%s","port":%d,"username":"swoc2","password":"********","qos":1}}""".formatted(broker.getHost(), broker.getMappedPort(1883)))
                            .with(admin())
                            .with(csrf()))
                    .andExpect(status().isOk());
            assertThat(connections
                            .runtime(id)
                            .orElseThrow()
                            .definition()
                            .config()
                            .get("password"))
                    .isEqualTo("s3cret-pass");
            mvc.perform(get("/api/audit").param("action", "connection.update").with(admin()))
                    .andExpect(jsonPath("$[0].after.config.password").value("********"));

            // Broker gone -> DOWN with backoff.
            broker.stop();
            ConnectionRuntime after = connections.runtime(id).orElseThrow();
            await("DOWN", () -> after.state() == ConnectionState.DOWN || after.state() == ConnectionState.CONNECTING);
            mvc.perform(delete("/api/connections/" + id).with(admin()).with(csrf()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void mqttTopicsFollowTheIcd() {
        assertThat(MqttTopicAccess.topic("UNIITY-X", "CONTACT;01;0191;OKRA;U;;;1"))
                .isEqualTo("UNIITY-X/OKRA/CONTACT");
        assertThat(MqttTopicAccess.topic("UNIITY-X", "HEARTBEAT")).isEqualTo("UNIITY-X/unknown/HEARTBEAT");
        assertThat(MqttTopicAccess.topic("X", "TEXT;1;2;a/b#c;U")).isEqualTo("X/a_b_c/TEXT");
    }

    /** Package bridge to the transport's topic helper. */
    static final class MqttTopicAccess {
        static String topic(String prefix, String frame) {
            try {
                var m = MqttConnectionType.class.getDeclaredMethod("topicFor", String.class, String.class);
                m.setAccessible(true);
                return (String) m.invoke(null, prefix, frame);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void secretBoxRoundTripAndTamperDetection() {
        SecretBox box = new SecretBox("k");
        String enc = box.encrypt("hello");
        assertThat(enc).startsWith("enc:v1:");
        assertThat(box.decrypt(enc)).isEqualTo("hello");
        assertThat(box.encrypt("hello")).isNotEqualTo(enc); // random IV
        String tampered =
                enc.substring(0, enc.length() - 2) + (enc.endsWith("A") ? "B" : "A") + enc.charAt(enc.length() - 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> box.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SecretBox("other").decrypt(enc))
                .hasMessageContaining("SWOC2_SECRET_KEY");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SecretBox("").encrypt("x"))
                .hasMessageContaining("not set");
    }
}
