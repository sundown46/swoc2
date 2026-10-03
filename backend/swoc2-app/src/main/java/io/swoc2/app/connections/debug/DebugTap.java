package io.swoc2.app.connections.debug;

import io.swoc2.sedap.codec.DecodeResult;
import io.swoc2.sedap.codec.DecodeWarning;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Debug tap (ARCHITECTURE §4 step 3, DBG-001 backend): keeps the most recent raw and parsed
 * messages per connection with their warnings, for the debug console. Bounded ring buffer; cheap
 * enough to be always on. The realtime debug stream follows with the debug console UI (M7).
 */
@Component
public class DebugTap {

    static final int CAPACITY = 2000;

    /** One entry of the debug console. */
    public record Entry(
            Instant time,
            String connectionId,
            String connectionName,
            String direction,
            String raw,
            String type,
            List<String> warnings,
            Map<String, String> meta) {}

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Clock clock = Clock.systemUTC();

    public void inbound(String connectionId, String name, String raw, DecodeResult result, Map<String, String> meta) {
        add(new Entry(
                clock.instant(),
                connectionId,
                name,
                "IN",
                raw.length() > 4096 ? raw.substring(0, 4096) + "..." : raw,
                result.rejected() ? null : result.message().type().name(),
                result.warnings().stream().map(DecodeWarning::toString).toList(),
                meta));
    }

    public void outbound(String connectionId, String name, String raw) {
        add(new Entry(clock.instant(), connectionId, name, "OUT", raw, null, List.of(), Map.of()));
    }

    public void note(String connectionId, String message) {
        add(new Entry(clock.instant(), connectionId, null, "NOTE", "", null, List.of(message), Map.of()));
    }

    private synchronized void add(Entry e) {
        entries.addLast(e);
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
    }

    /** Newest first, optionally only one connection. */
    public synchronized List<Entry> recent(String connectionId, int limit) {
        List<Entry> out = new ArrayList<>();
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Entry e = it.next();
            if (connectionId == null || connectionId.equals(e.connectionId())) {
                out.add(e);
            }
        }
        return out;
    }
}
