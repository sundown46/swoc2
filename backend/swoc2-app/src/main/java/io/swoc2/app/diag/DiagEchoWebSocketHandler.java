package io.swoc2.app.diag;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket probe for {@code /diag} (ARCHITECTURE §6 transport 1): echoes short text messages
 * back so the client can confirm the upgrade got through every proxy in between and measure the
 * round trip. Anonymous, so it is bounded: at most {@link #MAX_MESSAGES} messages of at most
 * {@link #MAX_MESSAGE_CHARS} characters per connection, and the server closes the connection
 * after {@link #MAX_LIFETIME} regardless.
 */
class DiagEchoWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DiagEchoWebSocketHandler.class);

    static final int MAX_MESSAGES = 10;
    static final int MAX_MESSAGE_CHARS = 256;
    static final Duration MAX_LIFETIME = Duration.ofSeconds(15);

    private final Map<String, AtomicInteger> messageCounts = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> lifetimeTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "diag-ws-lifetime");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_MESSAGE_CHARS * 4);
        messageCounts.put(session.getId(), new AtomicInteger());
        lifetimeTimers.put(
                session.getId(),
                scheduler.schedule(
                        () -> closeQuietly(session, CloseStatus.NORMAL.withReason("diag probe lifetime reached")),
                        MAX_LIFETIME.toMillis(),
                        TimeUnit.MILLISECONDS));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        AtomicInteger count = messageCounts.get(session.getId());
        if (count == null || count.incrementAndGet() > MAX_MESSAGES) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("diag probe message limit reached"));
            return;
        }
        String payload = message.getPayload();
        if (payload.length() > MAX_MESSAGE_CHARS) {
            closeQuietly(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        session.sendMessage(new TextMessage(payload));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        messageCounts.remove(session.getId());
        ScheduledFuture<?> timer = lifetimeTimers.remove(session.getId());
        if (timer != null) {
            timer.cancel(false);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // Expected whenever a browser tab closes mid-probe; not worth more than debug level.
        log.debug("diag WebSocket transport error on session {}", session.getId(), exception);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("Closing diag WebSocket session {} failed", session.getId(), e);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
