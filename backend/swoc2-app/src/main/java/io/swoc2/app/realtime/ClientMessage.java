package io.swoc2.app.realtime;

import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validated client-to-server message ({@code docs/realtime-protocol.md} §5). Parsing never throws:
 * anything malformed yields {@link Optional#empty()} and is dropped by the caller (CLAUDE.md
 * principle 1 - one bad message must not close the connection).
 */
record ClientMessage(String type, String topic) {

    /** Upper bound for one upstream envelope (§3). */
    static final int MAX_CHARS = 64 * 1024;

    static final String SUBSCRIBE = "subscribe";
    static final String UNSUBSCRIBE = "unsubscribe";
    static final String RESYNC = "resync";
    static final String PING = "ping";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9.-]{0,63}");

    static Optional<ClientMessage> parse(String json, ObjectMapper mapper) {
        if (json == null || json.length() > MAX_CHARS) {
            return Optional.empty();
        }
        try {
            return fromNode(mapper.readTree(json));
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }

    static Optional<ClientMessage> fromNode(JsonNode node) {
        if (node == null || !node.isObject() || node.path("v").asInt(-1) != Envelope.VERSION) {
            return Optional.empty();
        }
        String type = node.path("type").asString("");
        if (!NAME.matcher(type).matches()) {
            return Optional.empty();
        }
        String topic = node.path("payload").path("topic").asString("");
        if (!topic.isEmpty() && !NAME.matcher(topic).matches()) {
            return Optional.empty();
        }
        return Optional.of(new ClientMessage(type, topic.isEmpty() ? null : topic));
    }
}
