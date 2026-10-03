package io.swoc2.app.connections.transport;

import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SEDAP-Express over UDP unicast (ICD §4, standard port 50000): listens on a local port and sends
 * to one remote host/port. UDP has no connection, so the state is UP once the socket is bound.
 */
@Component
public class UdpUnicastConnectionType implements ConnectionType {

    public static final String ID = "sedap-udp";
    static final int MAX_DATAGRAM = 65_507;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SEDAP-Express UDP unicast";
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
                 "properties":{
                   "localPort":{"type":"integer","title":"Local port (receive)","minimum":1,"maximum":65535,"default":50000},
                   "bindAddress":{"type":"string","title":"Bind address","description":"Empty = all interfaces"},
                   "remoteHost":{"type":"string","title":"Remote host (send)","description":"Needed for OUT/BOTH"},
                   "remotePort":{"type":"integer","title":"Remote port (send)","minimum":1,"maximum":65535,"default":50000}
                 }}""";
    }

    @Override
    public Map<String, String> validate(Map<String, Object> config) {
        Map<String, String> errors = new LinkedHashMap<>();
        ConfigValues.port(config, "localPort", errors);
        if (present(config, "bindAddress")) {
            ConfigValues.host(config, "bindAddress", errors);
        }
        if (present(config, "remoteHost")) {
            ConfigValues.host(config, "remoteHost", errors);
            ConfigValues.port(config, "remotePort", errors);
        }
        return errors;
    }

    static boolean present(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    @Override
    public ConnectionInstance create(Map<String, Object> config, ConnectionContext context) {
        Map<String, String> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid config: " + errors);
        }
        Map<String, String> ignored = new LinkedHashMap<>();
        return new Instance(
                present(config, "bindAddress") ? ConfigValues.host(config, "bindAddress", ignored) : null,
                ConfigValues.port(config, "localPort", ignored),
                present(config, "remoteHost") ? ConfigValues.host(config, "remoteHost", ignored) : null,
                ConfigValues.port(config, "remotePort", ignored),
                context);
    }

    private static final class Instance implements ConnectionInstance {
        private final String bindAddress;
        private final int localPort;
        private final String remoteHost;
        private final int remotePort;
        private final ConnectionContext context;
        private volatile DatagramSocket socket;
        private volatile boolean stopped;

        Instance(String bindAddress, int localPort, String remoteHost, int remotePort, ConnectionContext context) {
            this.bindAddress = bindAddress;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.context = context;
        }

        @Override
        public void start() {
            stopped = false;
            Thread.ofVirtual().name("udp-" + context.connectionId()).start(this::run);
        }

        private void run() {
            String where = (bindAddress == null ? "*" : bindAddress) + ":" + localPort;
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(
                        bindAddress == null ? null : InetAddress.getByName(bindAddress), localPort));
                socket = s;
                context.state(
                        ConnectionState.UP,
                        "listening on udp " + where
                                + (remoteHost == null ? "" : ", sending to " + remoteHost + ":" + remotePort));
                byte[] buffer = new byte[MAX_DATAGRAM];
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                    s.receive(p);
                    Map<String, String> meta =
                            Map.of("remote", String.valueOf(p.getSocketAddress()), "transport", "udp");
                    for (String line : DatagramLines.split(p.getData(), p.getOffset(), p.getLength())) {
                        context.received(line, meta);
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!stopped) {
                    context.state(ConnectionState.DOWN, "udp " + where + ": " + e.getMessage());
                }
            } finally {
                socket = null;
            }
        }

        @Override
        public boolean send(String frame) {
            DatagramSocket s = socket;
            if (s == null || remoteHost == null) {
                return false;
            }
            byte[] bytes = (frame + "\n").getBytes(StandardCharsets.ISO_8859_1);
            if (bytes.length > MAX_DATAGRAM) {
                return false;
            }
            try {
                s.send(new DatagramPacket(bytes, bytes.length, new InetSocketAddress(remoteHost, remotePort)));
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void stop() {
            stopped = true;
            DatagramSocket s = socket;
            if (s != null) {
                s.close();
            }
        }
    }
}
