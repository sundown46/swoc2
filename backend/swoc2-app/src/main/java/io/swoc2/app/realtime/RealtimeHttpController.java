package io.swoc2.app.realtime;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP side of the realtime protocol ({@code docs/realtime-protocol.md}): session creation, the
 * SSE and long-poll downstream transports, and the upstream {@code POST /rt/send} used with both.
 */
@RestController
@RequestMapping("/rt")
class RealtimeHttpController {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHttpController.class);

    private final RealtimeSessionRegistry registry;
    private final RealtimeDispatcher dispatcher;
    private final RealtimeProperties properties;
    private final ObjectMapper mapper;

    RealtimeHttpController(
            RealtimeSessionRegistry registry,
            RealtimeDispatcher dispatcher,
            RealtimeProperties properties,
            ObjectMapper mapper) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostMapping("/session")
    ResponseEntity<Map<String, Object>> createSession(Principal principal) {
        RealtimeSession session = registry.create(principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "sessionId", session.id(),
                        "heartbeatMs", properties.heartbeat().toMillis(),
                        "pollHoldMs", properties.pollHold().toMillis()));
    }

    /** SSE downstream (transport 2). {@code Last-Event-ID} from a browser reconnect wins over {@code after}. */
    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter sse(
            Principal principal,
            @RequestParam String session,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        requireNonNegative(after);
        RealtimeSession rt = registry.get(session, principal.getName());
        long resumeAfter =
                lastEventId != null && lastEventId.matches("\\d{1,18}") ? Long.parseLong(lastEventId) : after;
        // No server-side timeout: liveness is the heartbeat; the session detaches on error/close.
        SseEmitter emitter = new SseEmitter(0L);
        DownstreamSink sink = new DownstreamSink() {
            @Override
            public String kind() {
                return "sse";
            }

            @Override
            public boolean send(List<Envelope> envelopes) {
                try {
                    for (Envelope e : envelopes) {
                        SseEmitter.SseEventBuilder event =
                                SseEmitter.event().data(mapper.writeValueAsString(e), MediaType.APPLICATION_JSON);
                        if (e.buffered()) {
                            event.id(Long.toString(e.seq()));
                        }
                        emitter.send(event);
                    }
                    return true;
                } catch (IOException | IllegalStateException gone) {
                    return false;
                }
            }

            @Override
            public void close(int code, String reason) {
                emitter.complete();
            }
        };
        emitter.onCompletion(() -> rt.detach(sink));
        emitter.onTimeout(() -> rt.detach(sink));
        emitter.onError(e -> rt.detach(sink));
        rt.attach(sink, resumeAfter);
        return emitter;
    }

    /**
     * Long-poll downstream (transport 3): answers immediately if envelopes after {@code after} are
     * buffered, otherwise holds until the next envelope or {@code pollHold}, then answers {@code []}.
     * One-shot: the sink detaches itself after answering.
     */
    @GetMapping("/poll")
    DeferredResult<List<Envelope>> poll(
            Principal principal, @RequestParam String session, @RequestParam(defaultValue = "0") long after) {
        requireNonNegative(after);
        RealtimeSession rt = registry.get(session, principal.getName());
        DeferredResult<List<Envelope>> result =
                new DeferredResult<>(properties.pollHold().toMillis(), List.of());
        DownstreamSink sink = new DownstreamSink() {
            @Override
            public String kind() {
                return "long-poll";
            }

            @Override
            public boolean wantsIdleHeartbeats() {
                return false;
            }

            @Override
            public boolean send(List<Envelope> envelopes) {
                result.setResult(new ArrayList<>(envelopes));
                return false; // one-shot
            }

            @Override
            public void close(int code, String reason) {
                result.setResult(List.of());
            }
        };
        result.onTimeout(() -> rt.detach(sink));
        result.onCompletion(() -> rt.detach(sink));
        result.onError(e -> rt.detach(sink));
        rt.attach(sink, after);
        return result;
    }

    /** Upstream for SSE and long-poll: one client message or a JSON array of them. */
    @PostMapping(path = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> send(Principal principal, @RequestParam String session, @RequestBody String body) {
        RealtimeSession rt = registry.get(session, principal.getName());
        if (body.length() > ClientMessage.MAX_CHARS) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (JacksonException malformed) {
            return ResponseEntity.badRequest().build();
        }
        List<JsonNode> messages = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(messages::add);
        } else {
            messages.add(node);
        }
        int dropped = 0;
        for (JsonNode m : messages) {
            var parsed = ClientMessage.fromNode(m);
            if (parsed.isPresent()) {
                dispatcher.handle(rt, parsed.get());
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("Dropped {} malformed realtime message(s) on session {}", dropped, rt.id());
        }
        return ResponseEntity.accepted().build();
    }

    private static void requireNonNegative(long after) {
        if (after < 0) {
            throw new IllegalArgumentException("after must be >= 0");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create("https://swoc2.example/problems/bad-request"));
        problem.setTitle("Bad request");
        return problem;
    }

    @ExceptionHandler(UnknownSessionException.class)
    ProblemDetail unknownSession(UnknownSessionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setType(URI.create("https://swoc2.example/problems/realtime-unknown-session"));
        problem.setTitle("Unknown realtime session");
        return problem;
    }
}
