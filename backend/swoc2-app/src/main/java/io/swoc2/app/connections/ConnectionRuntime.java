package io.swoc2.app.connections;

import io.swoc2.pluginapi.connection.ConnectionContext;
import io.swoc2.pluginapi.connection.ConnectionInstance;
import io.swoc2.pluginapi.connection.ConnectionState;
import io.swoc2.pluginapi.connection.ConnectionType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One connection at runtime (ARCHITECTURE §11.2): owns the type's instance, its state, metrics and
 * reconnect with exponential backoff (CON-003). Every call into the instance is guarded, so a
 * misbehaving connection type cannot affect other connections or the caller.
 */
public final class ConnectionRuntime implements ConnectionContext {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRuntime.class);
    static final Duration[] BACKOFF = {
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60)
    };

    private final ConnectionDefinition definition;
    private final ConnectionType type;
    private final FrameHandler handler;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final ConnectionMetrics metrics = new ConnectionMetrics();
    private final Logger connectionLog;
    private ConnectionInstance instance;
    private volatile ConnectionState state = ConnectionState.DISABLED;
    private volatile String stateDetail = "";
    private volatile Instant stateSince;
    private volatile Instant lastHeartbeat;
    private int failures;
    private ScheduledFuture<?> retry;
    private boolean running;

    ConnectionRuntime(
            ConnectionDefinition definition,
            ConnectionType type,
            FrameHandler handler,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this.definition = definition;
        this.type = type;
        this.handler = handler;
        this.scheduler = scheduler;
        this.clock = clock;
        this.stateSince = clock.instant();
        this.connectionLog = LoggerFactory.getLogger("connection." + definition.name());
    }

    public ConnectionDefinition definition() {
        return definition;
    }

    /** Frame format of this connection's type (selects codec and outbound eligibility). */
    public String frameFormat() {
        return type.frameFormat();
    }

    @Override
    public String connectionId() {
        return definition.id().toString();
    }

    synchronized void start() {
        running = true;
        failures = 0;
        launch();
    }

    private void launch() {
        try {
            instance = type.create(definition.config(), this);
            instance.start();
        } catch (RuntimeException e) {
            state(ConnectionState.DOWN, "start failed: " + e.getMessage());
        }
    }

    synchronized void stop() {
        running = false;
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
        stopInstance();
        setState(ConnectionState.DISABLED, "disabled");
    }

    private void stopInstance() {
        ConnectionInstance i = instance;
        instance = null;
        if (i != null) {
            try {
                i.stop();
            } catch (RuntimeException e) {
                log.warn("Connection {} failed to stop cleanly", definition.name(), e);
            }
        }
    }

    @Override
    public void received(String frame, Map<String, String> meta) {
        metrics.received(frame.getBytes(StandardCharsets.ISO_8859_1).length + 1, clock.instant());
        try {
            handler.handle(this, frame, meta);
        } catch (RuntimeException e) {
            // Handlers must not throw; if one does, count it and keep the connection alive.
            error("frame handling failed: " + e);
            log.error("Frame handler failed on connection {}", definition.name(), e);
        }
    }

    @Override
    public synchronized void state(ConnectionState newState, String detail) {
        if (!running && newState != ConnectionState.DISABLED) {
            return; // late report from a stopped instance
        }
        if (newState == ConnectionState.DOWN) {
            error(detail);
            stopInstance();
            Duration delay = BACKOFF[Math.min(failures, BACKOFF.length - 1)];
            failures++;
            // State and detail change together, so nobody sees DOWN without the retry information.
            setState(newState, detail + " - retry in " + delay.toSeconds() + " s");
            retry = scheduler.schedule(this::retry, delay.toMillis(), TimeUnit.MILLISECONDS);
            return;
        }
        setState(newState, detail);
        if (newState == ConnectionState.UP) {
            failures = 0;
        }
    }

    private synchronized void retry() {
        if (!running) {
            return;
        }
        metrics.reconnect();
        launch();
    }

    private void setState(ConnectionState newState, String detail) {
        if (newState != state) {
            stateSince = clock.instant();
            connectionLog.info("{} ({})", newState, detail);
        }
        stateDetail = detail == null ? "" : detail;
        state = newState;
    }

    @Override
    public Logger logger() {
        return connectionLog;
    }

    /** Sends one frame if the connection is up and allowed to send. */
    public boolean send(String frame) {
        ConnectionInstance i = instance;
        if (i == null || definition.direction() == io.swoc2.pluginapi.connection.Direction.IN) {
            return false;
        }
        try {
            boolean ok = i.send(frame);
            if (ok) {
                metrics.sent(frame.getBytes(StandardCharsets.ISO_8859_1).length + 1, clock.instant());
            }
            return ok;
        } catch (RuntimeException e) {
            error("send failed: " + e.getMessage());
            return false;
        }
    }

    public void error(String message) {
        metrics.error(message, clock.instant());
    }

    public void warning() {
        metrics.warning();
    }

    public void dropped() {
        metrics.dropped();
    }

    /** A SEDAP HEARTBEAT arrived (SDX-005: HEARTBEAT -> connection health). */
    public void heartbeat() {
        lastHeartbeat = clock.instant();
    }

    public ConnectionState state() {
        return state;
    }

    /** Runtime status for the connection manager (CON-002). */
    public record Status(
            ConnectionState state,
            String detail,
            Instant since,
            Instant lastHeartbeat,
            ConnectionMetrics.Snapshot metrics) {}

    public Status status() {
        return new Status(state, stateDetail, stateSince, lastHeartbeat, metrics.snapshot(clock.instant()));
    }
}
