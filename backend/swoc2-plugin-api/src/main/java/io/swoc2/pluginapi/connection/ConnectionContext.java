package io.swoc2.pluginapi.connection;

import java.util.Map;
import org.slf4j.Logger;

/** What the core offers a running connection. Thread-safe; may be called from any thread. */
public interface ConnectionContext {

    String connectionId();

    /**
     * Hands over one received frame. Never throws: decoding and validation happen in the core.
     *
     * @param meta transport details for the debug console, e.g. {@code remote=10.0.0.5:50000}
     */
    void received(String frame, Map<String, String> meta);

    /** Reports a state change, with a short human-readable detail (shown in the connection manager). */
    void state(ConnectionState state, String detail);

    /** Logger attributed to this connection. */
    Logger logger();
}
