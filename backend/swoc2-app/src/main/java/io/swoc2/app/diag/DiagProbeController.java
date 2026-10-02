package io.swoc2.app.diag;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP probes for the {@code /diag} page (GEN-010). Anonymous by design, so each probe is
 * bounded: the long-poll hold is capped, the SSE stream sends a fixed handful of events and
 * ends, and nothing blocks a request thread while waiting (a single small scheduler drives the
 * delays). The responses only describe what the server <em>saw</em> of the request (scheme,
 * whether it arrived as secure), which is what an operator needs to debug a reverse proxy.
 */
@RestController
@RequestMapping("/api/diag")
class DiagProbeController implements DisposableBean {

    /** Upper bound for the long-poll hold, so an anonymous caller cannot park connections. */
    static final Duration MAX_POLL_HOLD = Duration.ofSeconds(3);

    /** Number of SSE events per probe stream. */
    static final int SSE_EVENT_COUNT = 3;

    /** Spacing between SSE events; lets the client tell a streaming proxy from a buffering one. */
    static final Duration SSE_EVENT_INTERVAL = Duration.ofMillis(400);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "diag-probe-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    /** Plain request/response round trip, plus what the server saw of the request. */
    @GetMapping("/ping")
    Map<String, Object> ping(HttpServletRequest request) {
        return Map.of(
                "serverTime", Instant.now().toString(),
                "scheme", request.getScheme(),
                "secure", request.isSecure());
    }

    /**
     * Long-poll probe (ARCHITECTURE §6 transport 3): holds the response for {@code holdMs}
     * (capped at {@link #MAX_POLL_HOLD}), then answers. A proxy that cuts or buffers held
     * requests shows up on the client as an error or a much longer round trip.
     */
    @GetMapping("/poll")
    DeferredResult<Map<String, Object>> poll(@RequestParam(defaultValue = "1500") long holdMs) {
        long hold = Math.clamp(holdMs, 0, MAX_POLL_HOLD.toMillis());
        DeferredResult<Map<String, Object>> result = new DeferredResult<>(hold + 5_000);
        scheduler.schedule(
                () -> result.setResult(
                        Map.of("heldMs", hold, "serverTime", Instant.now().toString())),
                hold,
                TimeUnit.MILLISECONDS);
        return result;
    }

    /**
     * SSE probe (ARCHITECTURE §6 transport 2): {@link #SSE_EVENT_COUNT} events,
     * {@link #SSE_EVENT_INTERVAL} apart, then the stream completes. If the client receives all of
     * them at once at the end, something in between buffers SSE.
     */
    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter sse() {
        SseEmitter emitter = new SseEmitter(SSE_EVENT_INTERVAL.toMillis() * (SSE_EVENT_COUNT + 2) + 5_000);
        for (int i = 1; i <= SSE_EVENT_COUNT; i++) {
            int index = i;
            scheduler.schedule(
                    () -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("probe")
                                    .id(Integer.toString(index))
                                    .data(Map.of("index", index, "of", SSE_EVENT_COUNT)));
                            if (index == SSE_EVENT_COUNT) {
                                emitter.complete();
                            }
                        } catch (IOException | IllegalStateException clientGone) {
                            // Client disconnected mid-probe; nothing to clean up beyond this.
                            emitter.completeWithError(clientGone);
                        }
                    },
                    SSE_EVENT_INTERVAL.toMillis() * (i - 1),
                    TimeUnit.MILLISECONDS);
        }
        return emitter;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
