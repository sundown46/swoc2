package io.swoc2.app.realtime;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket transport ({@code docs/realtime-protocol.md} §1, transport 1): {@code /rt/ws?session=
 * &after=}. Downstream envelopes are text frames; upstream frames are client messages. Sends go
 * through a {@link ConcurrentWebSocketSessionDecorator} so a slow client never blocks the
 * publishing thread - it gets disconnected instead and resumes with {@code after}.
 */
class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private static final String ATTR_SINK = "rt.sink";
    private static final String ATTR_SESSION = "rt.session";

    private final RealtimeSessionRegistry registry;
    private final RealtimeDispatcher dispatcher;
    private final ObjectMapper mapper;

    RealtimeWebSocketHandler(RealtimeSessionRegistry registry, RealtimeDispatcher dispatcher, ObjectMapper mapper) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws IOException {
        ws.setTextMessageSizeLimit(ClientMessage.MAX_CHARS);
        Principal principal = ws.getPrincipal();
        Map<String, List<String>> query = ws.getUri() == null
                ? Map.of()
                : UriComponentsBuilder.fromUri(ws.getUri()).build().getQueryParams();
        String sessionId = first(query, "session");
        long after = parseAfter(first(query, "after"));
        if (principal == null || sessionId == null || after < 0) {
            ws.close(new CloseStatus(4400, "invalid request"));
            return;
        }
        RealtimeSession session;
        try {
            session = registry.get(sessionId, principal.getName());
        } catch (UnknownSessionException e) {
            ws.close(new CloseStatus(4404, "unknown session"));
            return;
        }
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(ws, 5_000, 1024 * 1024);
        DownstreamSink sink = new DownstreamSink() {
            @Override
            public String kind() {
                return "websocket";
            }

            @Override
            public boolean send(List<Envelope> envelopes) {
                if (!safe.isOpen()) {
                    return false;
                }
                try {
                    for (Envelope e : envelopes) {
                        safe.sendMessage(new TextMessage(mapper.writeValueAsString(e)));
                    }
                    return true;
                } catch (IOException | RuntimeException failed) {
                    log.debug("Realtime WebSocket send failed for session {}", sessionId, failed);
                    return false;
                }
            }

            @Override
            public void close(int code, String reason) {
                try {
                    safe.close(new CloseStatus(code, reason));
                } catch (IOException ignored) {
                    // already gone
                }
            }
        };
        ws.getAttributes().put(ATTR_SINK, sink);
        ws.getAttributes().put(ATTR_SESSION, session);
        session.attach(sink, after);
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        RealtimeSession session = (RealtimeSession) ws.getAttributes().get(ATTR_SESSION);
        if (session == null) {
            return;
        }
        ClientMessage.parse(message.getPayload(), mapper)
                .ifPresentOrElse(
                        m -> dispatcher.handle(session, m),
                        () -> log.debug("Dropping malformed realtime message on session {}", session.id()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        RealtimeSession session = (RealtimeSession) ws.getAttributes().get(ATTR_SESSION);
        DownstreamSink sink = (DownstreamSink) ws.getAttributes().get(ATTR_SINK);
        if (session != null && sink != null) {
            session.detach(sink);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable exception) {
        log.debug("Realtime WebSocket transport error", exception);
    }

    private static String first(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    static long parseAfter(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
