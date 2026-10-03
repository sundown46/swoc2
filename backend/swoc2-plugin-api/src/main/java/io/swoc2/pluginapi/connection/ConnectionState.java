package io.swoc2.pluginapi.connection;

/** Connection lifecycle (ARCHITECTURE §11.2). */
public enum ConnectionState {
    /** Switched off. */
    DISABLED,
    /** Trying to connect / bind. */
    CONNECTING,
    /** Working. */
    UP,
    /** Working with problems (e.g. server listening but no client connected). */
    DEGRADED,
    /** Failed; the core retries with backoff. */
    DOWN
}
