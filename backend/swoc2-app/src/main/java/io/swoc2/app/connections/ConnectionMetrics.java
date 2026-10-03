package io.swoc2.app.connections;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counters for one connection (CON-002): totals plus per-second rates over the last 10 s, using a
 * small ring of one-second buckets (no allocation on the hot path).
 */
public final class ConnectionMetrics {

    private static final int WINDOW = 10;

    private final AtomicLong messagesIn = new AtomicLong();
    private final AtomicLong messagesOut = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLongArray inBuckets = new AtomicLongArray(WINDOW);
    private final AtomicLongArray outBuckets = new AtomicLongArray(WINDOW);
    private final AtomicLongArray bucketSecond = new AtomicLongArray(WINDOW);
    private volatile Instant lastMessageAt;
    private volatile String lastError;
    private volatile Instant lastErrorAt;

    void received(int bytes, Instant now) {
        messagesIn.incrementAndGet();
        bytesIn.addAndGet(bytes);
        lastMessageAt = now;
        bump(inBuckets, now);
    }

    void sent(int bytes, Instant now) {
        messagesOut.incrementAndGet();
        bytesOut.addAndGet(bytes);
        bump(outBuckets, now);
    }

    void error(String message, Instant now) {
        errors.incrementAndGet();
        lastError = message;
        lastErrorAt = now;
    }

    void warning() {
        warnings.incrementAndGet();
    }

    void dropped() {
        dropped.incrementAndGet();
    }

    void reconnect() {
        reconnects.incrementAndGet();
    }

    private void bump(AtomicLongArray buckets, Instant now) {
        long second = now.getEpochSecond();
        int i = (int) (second % WINDOW);
        if (bucketSecond.getAndSet(i, second) != second) {
            inBuckets.set(i, 0);
            outBuckets.set(i, 0);
        }
        buckets.incrementAndGet(i);
    }

    private double rate(AtomicLongArray buckets, Instant now) {
        long current = now.getEpochSecond();
        long sum = 0;
        for (int i = 0; i < WINDOW; i++) {
            long s = bucketSecond.get(i);
            // Only complete seconds inside the window.
            if (s < current && s >= current - WINDOW) {
                sum += buckets.get(i);
            }
        }
        return sum / (double) WINDOW;
    }

    /** Immutable view for the API. */
    public record Snapshot(
            long messagesIn,
            long messagesOut,
            double messagesInPerSecond,
            double messagesOutPerSecond,
            long bytesIn,
            long bytesOut,
            long errors,
            long warnings,
            long dropped,
            long reconnects,
            Instant lastMessageAt,
            String lastError,
            Instant lastErrorAt) {}

    Snapshot snapshot(Instant now) {
        return new Snapshot(
                messagesIn.get(),
                messagesOut.get(),
                rate(inBuckets, now),
                rate(outBuckets, now),
                bytesIn.get(),
                bytesOut.get(),
                errors.get(),
                warnings.get(),
                dropped.get(),
                reconnects.get(),
                lastMessageAt,
                lastError,
                lastErrorAt);
    }
}
