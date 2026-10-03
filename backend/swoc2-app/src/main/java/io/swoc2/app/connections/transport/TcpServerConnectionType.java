package io.swoc2.app.connections.transport;

import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * SEDAP-Express over TCP, SWOC2 as server (ICD §4). Accepts up to {@code maxClients} clients, each
 * read on its own virtual thread; sending writes to every connected client. State is DEGRADED while
 * listening without clients, UP with at least one.
 */
@Component
public class TcpServerConnectionType implements ConnectionType {

    public static final String ID = "sedap-tcp-server";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SEDAP-Express TCP server";
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
                 "required":["port"],
                 "properties":{
                   "bindAddress":{"type":"string","title":"Bind address","description":"Empty = all interfaces"},
                   "port":{"type":"integer","title":"Port","minimum":1,"maximum":65535,"default":50000},
                   "maxClients":{"type":"integer","title":"Max. clients","minimum":1,"maximum":100,"default":10}
                 }}""";
    }

    @Override
    public Map<String, String> validate(Map<String, Object> config) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (config.get("bindAddress") != null && !"".equals(config.get("bindAddress"))) {
            ConfigValues.host(config, "bindAddress", errors);
        }
        ConfigValues.port(config, "port", errors);
        ConfigValues.positiveInt(config, "maxClients", 10, 100, errors);
        return errors;
    }

    @Override
    public ConnectionInstance create(Map<String, Object> config, ConnectionContext context) {
        Map<String, String> errors = validate(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid config: " + errors);
        }
        Object bind = config.get("bindAddress");
        return new Instance(
                bind instanceof String s && !s.isBlank() ? s.strip() : null,
                ConfigValues.port(config, "port", errors),
                ConfigValues.positiveInt(config, "maxClients", 10, 100, errors),
                context);
    }

    private static final class Instance implements ConnectionInstance {
        private final String bindAddress;
        private final int port;
        private final int maxClients;
        private final ConnectionContext context;
        private final Map<Socket, OutputStream> clients = new ConcurrentHashMap<>();
        private volatile ServerSocket server;
        private volatile boolean stopped;

        Instance(String bindAddress, int port, int maxClients, ConnectionContext context) {
            this.bindAddress = bindAddress;
            this.port = port;
            this.maxClients = maxClients;
            this.context = context;
        }

        @Override
        public void start() {
            stopped = false;
            Thread.ofVirtual().name("tcp-server-" + context.connectionId()).start(this::acceptLoop);
        }

        private void acceptLoop() {
            String where = (bindAddress == null ? "*" : bindAddress) + ":" + port;
            context.state(ConnectionState.CONNECTING, "binding " + where);
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(bindAddress == null ? null : InetAddress.getByName(bindAddress), port));
                server = s;
                reportClients(where);
                while (!stopped) {
                    Socket client = s.accept();
                    if (clients.size() >= maxClients) {
                        context.logger()
                                .warn(
                                        "Rejecting client {}: max {} clients",
                                        client.getRemoteSocketAddress(),
                                        maxClients);
                        client.close();
                        continue;
                    }
                    Thread.ofVirtual().name("tcp-server-client").start(() -> serve(client, where));
                }
            } catch (IOException | RuntimeException e) {
                if (!stopped) {
                    context.state(ConnectionState.DOWN, where + ": " + e.getMessage());
                }
            } finally {
                server = null;
                closeClients();
            }
        }

        private void serve(Socket client, String where) {
            String remote = String.valueOf(client.getRemoteSocketAddress());
            try (client) {
                client.setTcpNoDelay(true);
                clients.put(client, client.getOutputStream());
                reportClients(where);
                Map<String, String> meta = Map.of("remote", remote, "transport", "tcp-server");
                new LineReader(TcpClientConnectionType.MAX_LINE_BYTES)
                        .read(
                                client.getInputStream(),
                                line -> context.received(line, meta),
                                problem -> context.logger().warn("{}: {}", remote, problem));
            } catch (IOException e) {
                context.logger().debug("Client {} disconnected: {}", remote, e.getMessage());
            } finally {
                clients.remove(client);
                if (!stopped) {
                    reportClients(where);
                }
            }
        }

        private void reportClients(String where) {
            int n = clients.size();
            if (n == 0) {
                context.state(ConnectionState.DEGRADED, "listening on " + where + ", no client connected");
            } else {
                context.state(ConnectionState.UP, "listening on " + where + ", " + n + " client(s)");
            }
        }

        @Override
        public boolean send(String frame) {
            byte[] bytes = (frame + "\n").getBytes(StandardCharsets.ISO_8859_1);
            boolean any = false;
            for (Map.Entry<Socket, OutputStream> e : clients.entrySet()) {
                synchronized (e.getValue()) {
                    try {
                        e.getValue().write(bytes);
                        e.getValue().flush();
                        any = true;
                    } catch (IOException failed) {
                        try {
                            e.getKey().close();
                        } catch (IOException ignored) {
                            // gone anyway
                        }
                    }
                }
            }
            return any;
        }

        @Override
        public void stop() {
            stopped = true;
            ServerSocket s = server;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            closeClients();
        }

        private void closeClients() {
            for (Socket c : clients.keySet()) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            clients.clear();
        }
    }
}
