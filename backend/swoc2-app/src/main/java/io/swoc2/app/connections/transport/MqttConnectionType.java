package io.swoc2.app.connections.transport;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * SEDAP-Express over MQTT (ICD §4): publishes each message to {@code <prefix>/<senderid>/<type>}
 * (default prefix {@code UNIITY-X}) and subscribes to a configurable filter (default
 * {@code UNIITY-X/#}). MQTT 3.1.1 or 5, optional TLS and username/password; the password is a
 * secret ({@code writeOnly}), stored encrypted. Reconnects are done by the core with backoff, not by
 * the client library, so behaviour and metrics match the other transports.
 */
@Component
public class MqttConnectionType implements ConnectionType {

    public static final String ID = "sedap-mqtt";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SEDAP-Express MQTT";
    }

    @Override
    public String frameFormat() {
        return SEDAP_EXPRESS;
    }

    @Override
    public Set<Direction> directions() {
        return Set.of(Direction.IN, Direction.OUT, Direction.BOTH);
    }

    @Override
    public String configSchema() {
        return """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "required":["host"],
                 "properties":{
                   "host":{"type":"string","title":"Broker host"},
                   "port":{"type":"integer","title":"Broker port","minimum":1,"maximum":65535,"default":1883},
                   "tls":{"type":"boolean","title":"TLS","default":false},
                   "mqttVersion":{"type":"integer","title":"MQTT version","enum":[3,5],"default":5},
                   "username":{"type":"string","title":"Username"},
                   "password":{"type":"string","title":"Password","writeOnly":true},
                   "clientId":{"type":"string","title":"Client ID","description":"Empty = generated"},
                   "subscribe":{"type":"string","title":"Subscribe filter","default":"UNIITY-X/#"},
                   "topicPrefix":{"type":"string","title":"Publish topic prefix","default":"UNIITY-X"},
                   "qos":{"type":"integer","title":"QoS","enum":[0,1,2],"default":1}
                 }}""";
    }

    @Override
    public Map<String, String> validate(Map<String, Object> config) {
        Map<String, String> errors = new LinkedHashMap<>();
        ConfigValues.host(config, "host", errors);
        Object port = config.getOrDefault("port", 1883);
        if (!(port instanceof Number n) || n.intValue() < 1 || n.intValue() > 65535) {
            errors.put("port", "must be a port number 1-65535");
        }
        Object version = config.getOrDefault("mqttVersion", 5);
        if (!(version instanceof Number v) || (v.intValue() != 3 && v.intValue() != 5)) {
            errors.put("mqttVersion", "must be 3 or 5");
        }
        Object qos = config.getOrDefault("qos", 1);
        if (!(qos instanceof Number q) || q.intValue() < 0 || q.intValue() > 2) {
            errors.put("qos", "must be 0, 1 or 2");
        }
        Object tls = config.getOrDefault("tls", false);
        if (!(tls instanceof Boolean)) {
            errors.put("tls", "must be true or false");
        }
        ConfigValues.optionalText(config, "username", 256, errors);
        ConfigValues.optionalText(config, "password", 512, errors);
        String clientId = ConfigValues.optionalText(config, "clientId", 64, errors);
        if (clientId != null && !clientId.matches("[A-Za-z0-9_\\-.]+")) {
            errors.put("clientId", "letters, digits and _ - . only");
        }
        String filter = (String) config.getOrDefault("subscribe", "UNIITY-X/#");
        if (filter == null || filter.isBlank() || filter.length() > 256 || filter.contains("\u0000")) {
            errors.put("subscribe", "must be a topic filter like UNIITY-X/#");
        }
        String prefix = (String) config.getOrDefault("topicPrefix", "UNIITY-X");
        if (prefix == null
                || prefix.isBlank()
                || prefix.length() > 128
                || prefix.contains("#")
                || prefix.contains("+")) {
            errors.put("topicPrefix", "must be a topic prefix without wildcards");
        }
        return errors;
    }

    @Override
    public ConnectionInstance create(Map<String, Object> config, ConnectionContext context) {
        Map<String, String> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid config: " + errors);
        }
        Map<String, String> ignored = new LinkedHashMap<>();
        String clientId = ConfigValues.optionalText(config, "clientId", 64, ignored);
        return new Instance(
                (String) config.get("host"),
                ((Number) config.getOrDefault("port", 1883)).intValue(),
                Boolean.TRUE.equals(config.get("tls")),
                ((Number) config.getOrDefault("mqttVersion", 5)).intValue(),
                ConfigValues.optionalText(config, "username", 256, ignored),
                ConfigValues.optionalText(config, "password", 512, ignored),
                clientId != null
                        ? clientId
                        : "swoc2-" + UUID.randomUUID().toString().substring(0, 8),
                (String) config.getOrDefault("subscribe", "UNIITY-X/#"),
                (String) config.getOrDefault("topicPrefix", "UNIITY-X"),
                MqttQos.fromCode(((Number) config.getOrDefault("qos", 1)).intValue()),
                context);
    }

    /** Publish topic per ICD §4: {@code <prefix>/<senderid>/<messagetype>}, from the frame header. */
    static String topicFor(String prefix, String frame) {
        String[] parts = frame.split(";", 5);
        String type = parts[0].isBlank() ? "UNKNOWN" : parts[0];
        String sender = parts.length > 3 && !parts[3].isBlank() ? parts[3] : "unknown";
        // MQTT topic levels must not contain wildcards or separators.
        return prefix + "/" + sender.replaceAll("[#+/]", "_") + "/" + type.replaceAll("[#+/]", "_");
    }

    private static final class Instance implements ConnectionInstance {
        private final String host;
        private final int port;
        private final boolean tls;
        private final int version;
        private final String username;
        private final String password;
        private final String clientId;
        private final String filter;
        private final String prefix;
        private final MqttQos qos;
        private final ConnectionContext context;
        private volatile Mqtt5AsyncClient v5;
        private volatile Mqtt3AsyncClient v3;
        private volatile boolean stopped;
        private volatile boolean connected;

        Instance(
                String host,
                int port,
                boolean tls,
                int version,
                String username,
                String password,
                String clientId,
                String filter,
                String prefix,
                MqttQos qos,
                ConnectionContext context) {
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.version = version;
            this.username = username;
            this.password = password;
            this.clientId = clientId;
            this.filter = filter;
            this.prefix = prefix;
            this.qos = qos;
            this.context = context;
        }

        @Override
        public void start() {
            stopped = false;
            String broker = (tls ? "mqtts://" : "mqtt://") + host + ":" + port;
            context.state(ConnectionState.CONNECTING, "connecting to " + broker);
            Map<String, String> meta = Map.of("remote", broker, "transport", "mqtt");
            try {
                if (version == 5) {
                    var builder = MqttClient.builder()
                            .useMqttVersion5()
                            .identifier(clientId)
                            .serverHost(host)
                            .serverPort(port)
                            .addDisconnectedListener(ctx -> lost(broker, ctx.getCause()));
                    if (tls) {
                        builder = builder.sslWithDefaultConfig();
                    }
                    Mqtt5AsyncClient client = builder.buildAsync();
                    v5 = client;
                    var connect = client.connectWith().cleanStart(true);
                    if (username != null) {
                        connect = connect.simpleAuth()
                                .username(username)
                                .password(password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8))
                                .applySimpleAuth();
                    }
                    connect.send()
                            .thenCompose(ack -> client.subscribeWith()
                                    .topicFilter(filter)
                                    .qos(qos)
                                    .callback(p -> context.received(
                                            new String(p.getPayloadAsBytes(), StandardCharsets.ISO_8859_1).strip(),
                                            meta))
                                    .send())
                            .whenComplete((ok, error) -> afterConnect(broker, error));
                } else {
                    var builder = MqttClient.builder()
                            .useMqttVersion3()
                            .identifier(clientId)
                            .serverHost(host)
                            .serverPort(port)
                            .addDisconnectedListener(ctx -> lost(broker, ctx.getCause()));
                    if (tls) {
                        builder = builder.sslWithDefaultConfig();
                    }
                    Mqtt3AsyncClient client = builder.buildAsync();
                    v3 = client;
                    var connect = client.connectWith().cleanSession(true);
                    if (username != null) {
                        connect = connect.simpleAuth()
                                .username(username)
                                .password(password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8))
                                .applySimpleAuth();
                    }
                    connect.send()
                            .thenCompose(ack -> client.subscribeWith()
                                    .topicFilter(filter)
                                    .qos(qos)
                                    .callback(p -> context.received(
                                            new String(p.getPayloadAsBytes(), StandardCharsets.ISO_8859_1).strip(),
                                            meta))
                                    .send())
                            .whenComplete((ok, error) -> afterConnect(broker, error));
                }
            } catch (RuntimeException e) {
                context.state(ConnectionState.DOWN, broker + ": " + e.getMessage());
            }
        }

        private void afterConnect(String broker, Throwable error) {
            if (stopped) {
                return;
            }
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                context.state(ConnectionState.DOWN, broker + ": " + cause.getMessage());
            } else {
                connected = true;
                context.state(ConnectionState.UP, "connected to " + broker + ", subscribed " + filter);
            }
        }

        private void lost(String broker, Throwable cause) {
            if (connected && !stopped) {
                connected = false;
                context.state(
                        ConnectionState.DOWN,
                        broker + ": connection lost (" + (cause == null ? "?" : cause.getMessage()) + ")");
            }
        }

        @Override
        public boolean send(String frame) {
            if (!connected) {
                return false;
            }
            byte[] payload = frame.getBytes(StandardCharsets.ISO_8859_1);
            String topic = topicFor(prefix, frame);
            try {
                if (v5 != null) {
                    v5.publishWith()
                            .topic(topic)
                            .qos(qos)
                            .payload(payload)
                            .send()
                            .get(5, TimeUnit.SECONDS);
                } else if (v3 != null) {
                    v3.publishWith()
                            .topic(topic)
                            .qos(qos)
                            .payload(payload)
                            .send()
                            .get(5, TimeUnit.SECONDS);
                } else {
                    return false;
                }
                return true;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }

        @Override
        public void stop() {
            stopped = true;
            connected = false;
            try {
                if (v5 != null) {
                    v5.disconnect();
                }
                if (v3 != null) {
                    v3.disconnect();
                }
            } catch (RuntimeException ignored) {
                // shutting down anyway
            }
            v5 = null;
            v3 = null;
        }
    }
}
