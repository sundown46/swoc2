package io.swoc2.domain.picture;

import io.swoc2.domain.geo.GeoPosition;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of one contact in the live picture (ARCHITECTURE §5.1). Descriptive values
 * here are already resolved through the override layer (§5.2) when read via the picture store;
 * {@link #overridden()} tells which fields an operator changed.
 *
 * @param id internal id, stable for the lifetime of the contact in the store
 * @param key identity across updates (PIC-009)
 * @param sourceType e.g. {@code SEDAP_X}, {@code AIS}, {@code USER}
 * @param sourceIndicator source-specific origin, e.g. SEDAP source chars {@code AR}
 * @param course degrees true [0, 360), or null; speed m/s, or null
 * @param classification security classification of the latest message (P/U/R/C/S/T), or null
 * @param ids external ids such as {@code mmsi}, {@code icao}
 * @param raw every source field that has no place above (shown in the CAC)
 */
public record Contact(
        UUID id,
        SourceKey key,
        String sourceType,
        String sourceIndicator,
        ContactKind kind,
        SymbolCode symbol,
        Identity identity,
        Dimension dimension,
        String name,
        String remarks,
        GeoPosition position,
        Double course,
        Double heading,
        Double speed,
        Instant sourceTime,
        Instant receivedAt,
        ContactState state,
        Character classification,
        Map<String, String> ids,
        Map<String, String> raw,
        ContactOverride overridden) {

    public Contact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(state, "state");
        identity = identity == null ? Identity.UNKNOWN : identity;
        dimension = dimension == null ? Dimension.UNKNOWN : dimension;
        ids = ids == null ? Map.of() : Map.copyOf(ids);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
        overridden = overridden == null ? ContactOverride.NONE : overridden;
    }
}
