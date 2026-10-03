package io.swoc2.pluginapi.connection;

/**
 * One running connection. The core calls {@link #start()} and, after a failure reported through
 * {@link ConnectionContext#state}, {@link #stop()} and later {@link #start()} again with backoff.
 */
public interface ConnectionInstance {

    /**
     * Starts connecting/listening in the background and returns immediately. Progress and failures
     * are reported via {@link ConnectionContext#state}; received frames via {@link
     * ConnectionContext#received}.
     */
    void start();

    /** Stops and releases every resource (sockets, threads). Idempotent; must not block for long. */
    void stop();

    /**
     * Sends one frame (without terminator) if the connection can send right now.
     *
     * @return false if not connected or the frame could not be written
     */
    boolean send(String frame);
}
