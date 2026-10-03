package io.swoc2.app.realtime;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Handles client-to-server messages for a session, whichever transport they arrived on
 * ({@code docs/realtime-protocol.md} §5). Unknown types and topics are ignored with a debug log.
 */
@Component
class RealtimeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDispatcher.class);

    private final Map<String, Topic> topics;

    RealtimeDispatcher(List<Topic> topics) {
        this.topics = topics.stream().collect(Collectors.toUnmodifiableMap(Topic::name, Function.identity()));
    }

    void handle(RealtimeSession session, ClientMessage message) {
        switch (message.type()) {
            case ClientMessage.SUBSCRIBE, ClientMessage.RESYNC -> {
                Topic topic = message.topic() == null ? null : topics.get(message.topic());
                if (topic == null) {
                    session.publish(
                            Envelope.ERROR,
                            JsonNodeFactory.instance
                                    .objectNode()
                                    .put("code", "unknown-topic")
                                    .put("message", "Unknown topic"));
                    return;
                }
                // Subscribe first, then snapshot atomically w.r.t. the topic's deltas: every delta
                // after the snapshot reaches the session, none before it can overtake it.
                session.subscribe(topic.name());
                topic.sendSnapshot(session);
            }
            case ClientMessage.UNSUBSCRIBE -> {
                if (message.topic() != null) {
                    session.unsubscribe(message.topic());
                }
            }
            case ClientMessage.PING -> session.heartbeat(true);
            default -> log.debug("Ignoring unknown realtime message type {}", message.type());
        }
    }
}
