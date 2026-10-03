package io.swoc2.app.realtime;

import tools.jackson.databind.JsonNode;

/**
 * Server-to-client envelope ({@code docs/realtime-protocol.md} §3).
 *
 * @param v protocol version, always {@link #VERSION}
 * @param seq per-session sequence number; heartbeats carry the current value without incrementing
 * @param type message type (§4)
 * @param payload type-specific object
 */
record Envelope(int v, long seq, String type, JsonNode payload) {

    static final int VERSION = 1;

    static final String HELLO = "hello";
    static final String SNAPSHOT = "snapshot";
    static final String DELTA = "delta";
    static final String HEARTBEAT = "heartbeat";
    static final String ERROR = "error";

    boolean buffered() {
        return !HEARTBEAT.equals(type);
    }
}
