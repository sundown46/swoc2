package io.swoc2.app.connections.sedap;

import io.swoc2.domain.geo.GeoPosition;
import io.swoc2.domain.geo.Geodesy;
import io.swoc2.domain.picture.ContactKind;
import io.swoc2.domain.picture.ContactUpdate;
import io.swoc2.domain.picture.SourceKey;
import io.swoc2.domain.units.Angles;
import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.codec.FieldValue;
import io.swoc2.sedap.codec.SedapMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Maps SEDAP-Express CONTACT and OWNUNIT messages to the canonical model (SDX-005, ADR 0008). Units
 * from the ICD are already SI (metres, m/s, degrees); angles are normalised to [0, 360) here, the
 * only conversion needed (CLAUDE.md principle 5).
 */
final class SedapContactMapper {

    private SedapContactMapper() {}

    static final String SOURCE_TYPE = "SEDAP_X";

    /** Track id used for OWNUNITs: they have no ID field; the sender identifies them (ICD §6.1). */
    static String ownUnitTrack(SedapMessage m) {
        return m.header().sender() == null ? "OWNUNIT" : m.header().sender();
    }

    /** Result: an update, a delete, or a reason why the message cannot be used. */
    sealed interface Result {}

    record Upsert(ContactUpdate update) implements Result {}

    record Delete(SourceKey key) implements Result {}

    record Unusable(String reason) implements Result {}

    /**
     * @param referenceFor finds the reference position for relative X/Y/Z (ICD §6.2: the sender's
     *     OWNUNIT, else the receiver's own position)
     */
    static Result map(String connectionId, SedapMessage m, Function<String, Optional<GeoPosition>> referenceFor) {
        String sender = m.header().sender() == null ? "" : m.header().sender();
        if (m.type() == MessageType.OWNUNIT) {
            Optional<GeoPosition> pos = absolute(m);
            if (pos.isEmpty()) {
                return new Unusable("OWNUNIT without valid Latitude/Longitude");
            }
            return new Upsert(update(
                    new SourceKey(connectionId, sender, ownUnitTrack(m)), ContactKind.OWNUNIT, m, pos.get(), null));
        }
        if (m.type() != MessageType.CONTACT) {
            return new Unusable("not a CONTACT/OWNUNIT");
        }
        Optional<String> id = m.text("ContactID").map(String::strip).filter(s -> !s.isEmpty());
        if (id.isEmpty()) {
            return new Unusable("CONTACT without ContactID");
        }
        SourceKey key = new SourceKey(
                connectionId, sender, id.get().length() > 128 ? id.get().substring(0, 128) : id.get());
        if (m.value("DeleteFlag", Boolean.class).orElse(false)) {
            return new Delete(key);
        }
        Optional<GeoPosition> pos = absolute(m).or(() -> relative(m, referenceFor.apply(sender)));
        if (pos.isEmpty()) {
            return new Unusable("CONTACT without usable position (no Lat/Lon and no reference for relative X/Y)");
        }
        return new Upsert(
                update(key, ContactKind.CONTACT, m, pos.get(), m.text("Source").orElse(null)));
    }

    private static Optional<GeoPosition> absolute(SedapMessage m) {
        Optional<Double> lat = m.number("Latitude");
        Optional<Double> lon = m.number("Longitude");
        if (lat.isEmpty() || lon.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GeoPosition(
                lat.get(),
                lon.get(),
                m.number("Altitude").filter(Double::isFinite).orElse(null)));
    }

    /** ICD §2: x east, y north, z up, metres, relative to the reference. */
    private static Optional<GeoPosition> relative(SedapMessage m, Optional<GeoPosition> reference) {
        Optional<Double> x = m.number("relX-Distance");
        Optional<Double> y = m.number("relY-Distance");
        if (x.isEmpty() || y.isEmpty() || reference.isEmpty()) {
            return Optional.empty();
        }
        double distance = Math.hypot(x.get(), y.get());
        double bearing = Angles.normalize(Math.toDegrees(Math.atan2(x.get(), y.get())));
        GeoPosition p = Geodesy.direct(reference.get(), bearing, distance);
        Double baseAlt = reference.get().altitude();
        Double z = m.number("relZ-Distance").orElse(null);
        Double alt = z == null ? baseAlt : (baseAlt == null ? 0.0 : baseAlt) + z;
        return Optional.of(new GeoPosition(p.latitude(), p.longitude(), alt));
    }

    private static ContactUpdate update(
            SourceKey key, ContactKind kind, SedapMessage m, GeoPosition pos, String sourceIndicator) {
        Map<String, String> ids = new LinkedHashMap<>();
        m.text("MMSI").map(String::strip).filter(s -> !s.isEmpty()).ifPresent(v -> ids.put("mmsi", v));
        m.text("ICAO").map(String::strip).filter(s -> !s.isEmpty()).ifPresent(v -> ids.put("icao", v));
        Map<String, String> raw = new LinkedHashMap<>();
        for (FieldValue f : m.fields()) {
            if (f.raw() != null && !f.raw().isEmpty()) {
                raw.put(f.name(), f.raw().length() > 2048 ? f.raw().substring(0, 2048) + "..." : f.raw());
            }
        }
        return new ContactUpdate(
                key,
                SOURCE_TYPE,
                sourceIndicator,
                kind,
                m.text("SIDC").filter(s -> s.length() == 15).orElse(null),
                m.text("Name").map(String::strip).filter(s -> !s.isEmpty()).orElse(null),
                pos,
                m.number("CourseOverGround").map(Angles::normalize).orElse(null),
                m.number("Heading").map(Angles::normalize).orElse(null),
                m.number("SpeedOverGround").filter(v -> v >= 0).orElse(null),
                m.header().time(),
                m.header().classification(),
                ids,
                raw);
    }
}
