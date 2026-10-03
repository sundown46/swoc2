package io.swoc2.domain.picture;

import io.swoc2.domain.geo.GeoPosition;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One report about a contact from an adapter (e.g. a decoded SEDAP CONTACT). {@code null} means
 * "not reported this time": the picture store keeps the previous value for descriptive fields, but
 * kinematics (course/heading/speed) are taken as reported, because a missing course in a fresh
 * report must not leave a stale one on the map.
 */
public record ContactUpdate(
        SourceKey key,
        String sourceType,
        String sourceIndicator,
        ContactKind kind,
        String sidc,
        String name,
        GeoPosition position,
        Double course,
        Double heading,
        Double speed,
        Instant sourceTime,
        Character classification,
        Map<String, String> ids,
        Map<String, String> raw) {

    public ContactUpdate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        ids = ids == null ? Map.of() : Map.copyOf(ids);
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }
}
