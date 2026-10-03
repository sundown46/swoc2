package io.swoc2.app.realtime;

import java.util.List;

/**
 * One attached transport, seen from the session ({@code docs/realtime-protocol.md} §1). Exactly
 * one sink is attached to a session at a time.
 */
interface DownstreamSink {

    /** {@code websocket}, {@code sse} or {@code long-poll}; for logs and diagnostics. */
    String kind();

    /**
     * Delivers envelopes in order. Must not block for long (called with the session lock held);
     * implementations hand off to the container's async machinery.
     *
     * @return {@code false} if the sink can take no more envelopes (one-shot long-poll answered,
     *     connection gone); the session then detaches it
     */
    boolean send(List<Envelope> envelopes);

    /**
     * Whether idle heartbeats should be pushed. Long-polling says no: the held request itself is
     * the liveness check, and a heartbeat would just end every hold early (§7).
     */
    default boolean wantsIdleHeartbeats() {
        return true;
    }

    /** Closes the transport, e.g. because another transport replaced it (WS code 4409). */
    void close(int code, String reason);
}
