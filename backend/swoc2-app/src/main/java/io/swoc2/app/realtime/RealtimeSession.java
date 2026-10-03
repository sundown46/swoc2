package io.swoc2.app.realtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * One realtime session ({@code docs/realtime-protocol.md} §1-§7): owner, sequence counter, replay
 * buffer, subscriptions and the currently attached transport. All state changes are
 * {@code synchronized} on the session, so envelopes always leave in {@code seq} order no matter
 * which thread publishes.
 */
final class RealtimeSession {

    /** Close code when another transport takes over (§9). */
    static final int CLOSE_REPLACED = 4409;

    private final String id;
    private final String owner;
    private final long serial;
    private final int bufferSize;
    private final Clock clock;
    private final Deque<Envelope> buffer = new ArrayDeque<>();
    private final Set<String> topics = new LinkedHashSet<>();
    private long seq;
    private DownstreamSink sink;
    private Instant lastActivity;
    private Instant lastSent;
    private boolean closed;

    RealtimeSession(String id, String owner, long serial, int bufferSize, Clock clock) {
        this.id = id;
        this.owner = owner;
        this.serial = serial;
        this.bufferSize = bufferSize;
        this.clock = clock;
        this.lastActivity = clock.instant();
        this.lastSent = clock.instant();
    }

    String id() {
        return id;
    }

    String owner() {
        return owner;
    }

    /** Creation order within the registry; lower is older. */
    long serial() {
        return serial;
    }

    synchronized long seq() {
        return seq;
    }

    synchronized Set<String> topics() {
        return Set.copyOf(topics);
    }

    synchronized boolean subscribe(String topic) {
        touch();
        return topics.add(topic);
    }

    synchronized void unsubscribe(String topic) {
        touch();
        topics.remove(topic);
    }

    synchronized boolean isSubscribed(String topic) {
        return topics.contains(topic);
    }

    synchronized String attachedKind() {
        return sink == null ? null : sink.kind();
    }

    /** Appends an envelope (assigning the next seq) and pushes it to the attached transport. */
    synchronized Envelope publish(String type, JsonNode payload) {
        Envelope envelope = new Envelope(Envelope.VERSION, ++seq, type, payload);
        buffer.addLast(envelope);
        while (buffer.size() > bufferSize) {
            buffer.removeFirst();
        }
        push(List.of(envelope));
        return envelope;
    }

    /**
     * Sends a heartbeat (not buffered, current seq) to the attached transport, if any.
     *
     * @param requested answer to a client {@code ping} (always sent) rather than an idle heartbeat
     */
    synchronized void heartbeat(boolean requested) {
        if (sink != null && (requested || sink.wantsIdleHeartbeats())) {
            push(List.of(
                    new Envelope(Envelope.VERSION, seq, Envelope.HEARTBEAT, JsonNodeFactory.instance.objectNode())));
        }
    }

    /** True if a transport is attached and nothing was sent for at least {@code idleMillis}. */
    synchronized boolean idleFor(long idleMillis) {
        return sink != null && clock.millis() - lastSent.toEpochMilli() >= idleMillis;
    }

    /**
     * Attaches a transport, replacing (and closing) any previous one, and replays everything after
     * {@code after} (§6). If {@code after} is older than the buffer, the client must resync: an
     * {@code error resync-required} envelope is sent instead of a partial replay.
     */
    synchronized void attach(DownstreamSink newSink, long after) {
        touch();
        if (closed) {
            newSink.close(4404, "session closed");
            return;
        }
        if (sink != null && sink != newSink) {
            sink.close(CLOSE_REPLACED, "replaced by " + newSink.kind());
        }
        sink = newSink;
        List<Envelope> replay = new ArrayList<>();
        long oldest = buffer.isEmpty() ? seq + 1 : buffer.peekFirst().seq();
        if (after < oldest - 1 && after < seq) {
            Envelope resync = new Envelope(
                    Envelope.VERSION,
                    ++seq,
                    Envelope.ERROR,
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("code", "resync-required")
                            .put("message", "Requested position is older than the replay buffer"));
            buffer.addLast(resync);
            replay.add(resync);
        } else {
            for (Envelope e : buffer) {
                if (e.seq() > after) {
                    replay.add(e);
                }
            }
        }
        if (!replay.isEmpty()) {
            push(replay);
        }
    }

    /** Detaches the given transport if it is still the attached one (connection closed). */
    synchronized void detach(DownstreamSink oldSink) {
        if (sink == oldSink) {
            sink = null;
            touch();
        }
    }

    synchronized boolean expired(long ttlMillis) {
        return sink == null && clock.millis() - lastActivity.toEpochMilli() >= ttlMillis;
    }

    /** Closes the session for good (expiry, session limit). */
    synchronized void close(int code, String reason) {
        closed = true;
        if (sink != null) {
            sink.close(code, reason);
            sink = null;
        }
    }

    synchronized boolean closed() {
        return closed;
    }

    private void push(List<Envelope> envelopes) {
        if (sink == null) {
            return;
        }
        lastSent = clock.instant();
        boolean stillOpen;
        try {
            stillOpen = sink.send(envelopes);
        } catch (RuntimeException failed) {
            stillOpen = false;
        }
        if (!stillOpen) {
            // Buffered envelopes are replayed when the client re-attaches with its last seq.
            sink = null;
            touch();
        }
    }

    private void touch() {
        lastActivity = clock.instant();
    }
}
