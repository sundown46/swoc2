package io.swoc2.app.connections.transport;

import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SEDAP-Express over UDP multicast (ICD §4: group 228.2.19.80 / ff02:8:2:19:80::1, port 50000).
 * Joins the group on the given interface, receives from it and sends to it. Own packets come back
 * via loopback and are dropped by the ingest's own-sender rule (CON-005).
 */
@Component
public class UdpMulticastConnectionType implements ConnectionType {

    public static final String ID = "sedap-udp-multicast";
    static final String DEFAULT_GROUP = "228.2.19.80";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SEDAP-Express UDP multicast";
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
                   "group":{"type":"string","title":"Multicast group","default":"228.2.19.80"},
                   "port":{"type":"integer","title":"Port","minimum":1,"maximum":65535,"default":50000},
                   "networkInterface":{"type":"string","title":"Network interface","description":"e.g. eth0; empty = system default"},
                   "ttl":{"type":"integer","title":"TTL (hops) for sending","minimum":1,"maximum":255,"default":1}
                 }}""";
    }

    @Override
    public Map<String, String> validate(Map<String, Object> config) {
        Map<String, String> errors = new LinkedHashMap<>();
        Object group = config.getOrDefault("group", DEFAULT_GROUP);
        if (!(group instanceof String g) || !isMulticast(g)) {
            errors.put("group", "must be a multicast address (224.0.0.0/4 or ff00::/8)");
        }
        ConfigValues.port(config, "port", errors);
        ConfigValues.positiveInt(config, "ttl", 1, 255, errors);
        String nif = ConfigValues.optionalText(config, "networkInterface", 32, errors);
        if (nif != null && !nif.matches("[A-Za-z0-9_.:\\-]+")) {
            errors.put("networkInterface", "must be an interface name like eth0");
        }
        return errors;
    }

    private static boolean isMulticast(String address) {
        if (!address.matches("[0-9A-Fa-f.:]+")) {
            return false; // no DNS lookups for a group address
        }
        try {
            return InetAddress.getByName(address).isMulticastAddress();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ConnectionInstance create(Map<String, Object> config, ConnectionContext context) {
        Map<String, String> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid config: " + errors);
        }
        Map<String, String> ignored = new LinkedHashMap<>();
        return new Instance(
                (String) config.getOrDefault("group", DEFAULT_GROUP),
                ConfigValues.port(config, "port", ignored),
                ConfigValues.optionalText(config, "networkInterface", 32, ignored),
                ConfigValues.positiveInt(config, "ttl", 1, 255, ignored),
                context);
    }

    private static final class Instance implements ConnectionInstance {
        private final String group;
        private final int port;
        private final String networkInterface;
        private final int ttl;
        private final ConnectionContext context;
        private volatile MulticastSocket socket;
        private volatile boolean stopped;

        Instance(String group, int port, String networkInterface, int ttl, ConnectionContext context) {
            this.group = group;
            this.port = port;
            this.networkInterface = networkInterface;
            this.ttl = ttl;
            this.context = context;
        }

        @Override
        public void start() {
            stopped = false;
            Thread.ofVirtual().name("udp-mc-" + context.connectionId()).start(this::run);
        }

        private void run() {
            String where = group + ":" + port + (networkInterface == null ? "" : " on " + networkInterface);
            try (MulticastSocket s = new MulticastSocket(port)) {
                NetworkInterface nif = networkInterface == null ? null : NetworkInterface.getByName(networkInterface);
                if (networkInterface != null && nif == null) {
                    throw new IOException("no network interface " + networkInterface);
                }
                if (nif != null) {
                    s.setNetworkInterface(nif);
                }
                s.setTimeToLive(ttl);
                s.joinGroup(new InetSocketAddress(InetAddress.getByName(group), port), nif);
                socket = s;
                context.state(ConnectionState.UP, "joined " + where);
                byte[] buffer = new byte[UdpUnicastConnectionType.MAX_DATAGRAM];
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                    s.receive(p);
                    Map<String, String> meta =
                            Map.of("remote", String.valueOf(p.getSocketAddress()), "transport", "udp-multicast");
                    for (String line : DatagramLines.split(p.getData(), p.getOffset(), p.getLength())) {
                        context.received(line, meta);
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!stopped) {
                    context.state(ConnectionState.DOWN, "multicast " + where + ": " + e.getMessage());
                }
            } finally {
                socket = null;
            }
        }

        @Override
        public boolean send(String frame) {
            MulticastSocket s = socket;
            if (s == null) {
                return false;
            }
            byte[] bytes = (frame + "\n").getBytes(StandardCharsets.ISO_8859_1);
            if (bytes.length > UdpUnicastConnectionType.MAX_DATAGRAM) {
                return false;
            }
            try {
                s.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(group), port));
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void stop() {
            stopped = true;
            MulticastSocket s = socket;
            if (s != null) {
                s.close();
            }
        }
    }
}
