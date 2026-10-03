package io.swoc2.app.realtime;

import tools.jackson.databind.JsonNode;

/**
 * A stream of state a client can subscribe to ({@code docs/realtime-protocol.md} §4/§5). A topic
 * provides snapshots on demand and pushes deltas through {@link RealtimeSessionRegistry#publish}.
 */
interface Topic {

    String name();

    /** Full current state as a {@code snapshot} payload ({@code {topic, items}}). */
    JsonNode snapshot();

    /**
     * Publishes a snapshot to one session. Topics that publish deltas concurrently must override
     * this so that creating and publishing the snapshot is atomic with respect to their deltas;
     * otherwise a delta could overtake the snapshot it is based on.
     */
    default void sendSnapshot(RealtimeSession session) {
        session.publish(Envelope.SNAPSHOT, snapshot());
    }
}
