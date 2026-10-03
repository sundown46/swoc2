package io.swoc2.app.connections.sedap;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers recently seen message identities (ARCHITECTURE §11.3) to break multi-hop loops between
 * gateways: {@code (sender, type, number, time)} seen within the TTL is a duplicate. Bounded in
 * size; oldest entries are evicted first.
 */
final class DedupCache {

    private final Duration ttl;
    private final int maxEntries;
    private final LinkedHashMap<String, Instant> seen;

    DedupCache(Duration ttl, int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.seen = new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                return size() > DedupCache.this.maxEntries;
            }
        };
    }

    /** True if this key was seen within the TTL (and records it otherwise). */
    synchronized boolean isDuplicate(String key, Instant now) {
        Instant previous = seen.get(key);
        if (previous != null && Duration.between(previous, now).compareTo(ttl) < 0) {
            return true;
        }
        seen.remove(key);
        seen.put(key, now);
        return false;
    }
}
