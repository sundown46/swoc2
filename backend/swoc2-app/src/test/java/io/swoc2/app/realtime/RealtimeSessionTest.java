package io.swoc2.app.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/** Sequence, replay, resync and transport-switch rules of {@code docs/realtime-protocol.md} §3-§7. */
class RealtimeSessionTest {

    /** Records what a transport received; optionally one-shot like long-polling. */
    static final class RecordingSink implements DownstreamSink {
        final List<Envelope> received = new ArrayList<>();
        final boolean oneShot;
        Integer closedWith;

        RecordingSink(boolean oneShot) {
            this.oneShot = oneShot;
        }

        @Override
        public String kind() {
            return oneShot ? "long-poll" : "websocket";
        }

        @Override
        public boolean send(List<Envelope> envelopes) {
            received.addAll(envelopes);
            return !oneShot;
        }

        @Override
        public void close(int code, String reason) {
            closedWith = code;
        }

        List<Long> seqs() {
            return received.stream().map(Envelope::seq).toList();
        }
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    private RealtimeSession session(int buffer) {
        return new RealtimeSession("s1", "alice", 1, buffer, clock);
    }

    private static void publish(RealtimeSession s, int n) {
        for (int i = 0; i < n; i++) {
            s.publish(Envelope.DELTA, JsonNodeFactory.instance.objectNode());
        }
    }

    @Test
    void seqStartsAtOneAndIncrements() {
        RealtimeSession s = session(10);
        RecordingSink sink = new RecordingSink(false);
        s.attach(sink, 0);

        publish(s, 3);

        assertThat(sink.seqs()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void reattachReplaysOnlyWhatTheClientMissed() {
        RealtimeSession s = session(10);
        publish(s, 5);
        RecordingSink sink = new RecordingSink(false);

        s.attach(sink, 3);

        assertThat(sink.seqs()).containsExactly(4L, 5L);
    }

    @Test
    void tooOldPositionRequiresResync() {
        RealtimeSession s = session(3);
        publish(s, 10); // buffer holds 8, 9, 10
        RecordingSink sink = new RecordingSink(false);

        s.attach(sink, 2);

        assertThat(sink.received).hasSize(1);
        assertThat(sink.received.getFirst().type()).isEqualTo(Envelope.ERROR);
        assertThat(sink.received.getFirst().payload().path("code").asString()).isEqualTo("resync-required");
        assertThat(sink.received.getFirst().seq()).isEqualTo(11);
    }

    @Test
    void positionJustBeforeTheBufferStillReplays() {
        RealtimeSession s = session(3);
        publish(s, 10); // 8, 9, 10
        RecordingSink sink = new RecordingSink(false);

        s.attach(sink, 7);

        assertThat(sink.seqs()).containsExactly(8L, 9L, 10L);
    }

    @Test
    void newTransportReplacesTheOldOne() {
        RealtimeSession s = session(10);
        RecordingSink ws = new RecordingSink(false);
        RecordingSink sse = new RecordingSink(false);
        s.attach(ws, 0);
        publish(s, 2);

        s.attach(sse, 2);
        publish(s, 1);

        assertThat(ws.closedWith).isEqualTo(RealtimeSession.CLOSE_REPLACED);
        assertThat(ws.seqs()).containsExactly(1L, 2L);
        assertThat(sse.seqs()).containsExactly(3L);
    }

    @Test
    void longPollIsOneShotAndMissedEnvelopesWaitInTheBuffer() {
        RealtimeSession s = session(10);
        RecordingSink poll1 = new RecordingSink(true);
        s.attach(poll1, 0);
        publish(s, 1); // answers poll1, detaches it
        publish(s, 2); // nobody attached: buffered

        RecordingSink poll2 = new RecordingSink(true);
        s.attach(poll2, 1);

        assertThat(poll1.seqs()).containsExactly(1L);
        assertThat(poll2.seqs()).containsExactly(2L, 3L);
        assertThat(s.attachedKind()).isNull();
    }

    @Test
    void heartbeatsAreNotBufferedAndKeepTheSeq() {
        RealtimeSession s = session(10);
        RecordingSink sink = new RecordingSink(false);
        s.attach(sink, 0);
        publish(s, 1);

        s.heartbeat(false);
        publish(s, 1);

        assertThat(sink.received).extracting(Envelope::type).containsExactly("delta", "heartbeat", "delta");
        assertThat(sink.seqs()).containsExactly(1L, 1L, 2L);
        RecordingSink late = new RecordingSink(false);
        s.attach(late, 0);
        assertThat(late.received).extracting(Envelope::type).doesNotContain("heartbeat");
    }

    @Test
    void failingTransportIsDetachedNotFatal() {
        RealtimeSession s = session(10);
        DownstreamSink broken = new DownstreamSink() {
            @Override
            public String kind() {
                return "websocket";
            }

            @Override
            public boolean send(List<Envelope> envelopes) {
                throw new IllegalStateException("socket gone");
            }

            @Override
            public void close(int code, String reason) {}
        };
        s.attach(broken, 0);

        publish(s, 1);

        assertThat(s.attachedKind()).isNull();
        assertThat(s.seq()).isEqualTo(1);
    }

    @Test
    void malformedClientMessagesAreRejected() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(ClientMessage.parse("{\"v\":1,\"type\":\"subscribe\",\"payload\":{\"topic\":\"demo\"}}", mapper))
                .contains(new ClientMessage("subscribe", "demo"));
        assertThat(ClientMessage.parse("not json", mapper)).isEmpty();
        assertThat(ClientMessage.parse("{\"v\":2,\"type\":\"ping\"}", mapper)).isEmpty();
        assertThat(ClientMessage.parse("{\"v\":1,\"type\":\"<script>\"}", mapper))
                .isEmpty();
        assertThat(ClientMessage.parse("[1,2]", mapper)).isEmpty();
        assertThat(ClientMessage.parse("{\"v\":1,\"type\":\"ping\",\"pad\":\"" + "x".repeat(70_000) + "\"}", mapper))
                .isEmpty();
    }
}
