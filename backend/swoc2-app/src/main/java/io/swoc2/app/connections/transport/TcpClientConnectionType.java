package io.swoc2.app.connections.transport;

import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import io.swoc2.pluginapi.connection.Direction;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SEDAP-Express over TCP, SWOC2 as client (ICD §4: one persistent connection for all message
 * kinds). Connects, reads lines on a virtual thread, writes lines on demand. A lost connection is
 * reported as DOWN; the core reconnects with backoff.
 */
@Component
public class TcpClientConnectionType implements ConnectionType {

    public static final String ID = "sedap-tcp-client";
    static final int MAX_LINE_BYTES = 1 << 20;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SEDAP-Express TCP client";
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
                 "required":["host","port"],
                 "properties":{
                   "host":{"type":"string","title":"Host","description":"Host name or IP of the SEDAP-Express server"},
                   "port":{"type":"integer","title":"Port","minimum":1,"maximum":65535,"default":50000},
                   "connectTimeoutSeconds":{"type":"integer","title":"Connect timeout (s)","minimum":1,"maximum":60,"default":5}
                 }}""";
    }

    @Override
    public Map<String, String> validate(Map<String, Object> config) {
        Map<String, String> errors = new LinkedHashMap<>();
        ConfigValues.host(config, "host", errors);
        ConfigValues.port(config, "port", errors);
        ConfigValues.positiveInt(config, "connectTimeoutSeconds", 5, 60, errors);
        return errors;
    }

    @Override
    public ConnectionInstance create(Map<String, Object> config, ConnectionContext context) {
        Map<String, String> errors = new LinkedHashMap<>();
        String host = ConfigValues.host(config, "host", errors);
        int port = ConfigValues.port(config, "port", errors);
        int timeout = ConfigValues.positiveInt(config, "connectTimeoutSeconds", 5, 60, errors);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid config: " + errors);
        }
        return new Instance(host, port, timeout * 1000, context);
    }

    private static final class Instance implements ConnectionInstance {
        private final String host;
        private final int port;
        private final int timeoutMillis;
        private final ConnectionContext context;
        private volatile Socket socket;
        private volatile OutputStream out;
        private volatile Thread reader;
        private volatile boolean stopped;

        Instance(String host, int port, int timeoutMillis, ConnectionContext context) {
            this.host = host;
            this.port = port;
            this.timeoutMillis = timeoutMillis;
            this.context = context;
        }

        @Override
        public void start() {
            stopped = false;
            reader = Thread.ofVirtual()
                    .name("tcp-client-" + context.connectionId())
                    .start(this::run);
        }

        private void run() {
            String remote = host + ":" + port;
            context.state(ConnectionState.CONNECTING, "connecting to " + remote);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), timeoutMillis);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                socket = s;
                out = s.getOutputStream();
                context.state(ConnectionState.UP, "connected to " + remote);
                Map<String, String> meta = Map.of("remote", remote, "transport", "tcp");
                new LineReader(MAX_LINE_BYTES)
                        .read(
                                s.getInputStream(),
                                line -> context.received(line, meta),
                                problem -> context.logger().warn("{}: {}", remote, problem));
                if (!stopped) {
                    context.state(ConnectionState.DOWN, "connection closed by " + remote);
                }
            } catch (IOException | RuntimeException e) {
                if (!stopped) {
                    context.state(ConnectionState.DOWN, remote + ": " + e.getMessage());
                }
            } finally {
                out = null;
                socket = null;
            }
        }

        @Override
        public synchronized boolean send(String frame) {
            OutputStream o = out;
            if (o == null) {
                return false;
            }
            try {
                o.write((frame + "\n").getBytes(StandardCharsets.ISO_8859_1));
                o.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void stop() {
            stopped = true;
            Socket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            Thread t = reader;
            if (t != null) {
                t.interrupt();
            }
        }
    }
}
