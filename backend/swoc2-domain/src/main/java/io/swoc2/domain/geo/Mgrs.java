package io.swoc2.domain.geo;

import java.text.ParseException;
import java.util.Optional;
import mil.nga.grid.features.Point;
import mil.nga.mgrs.MGRS;
import mil.nga.mgrs.grid.GridType;

/**
 * MGRS conversion via the NGA library (ARCHITECTURE §3, MAP-016). MGRS is defined between 80°S
 * and 84°N; outside (UPS polar areas) {@link #toMgrs} returns empty instead of a wrong string.
 */
public final class Mgrs {

    private Mgrs() {}

    /** Precision in metres: 1, 10, 100, 1000 or 10000 (= 5 .. 1 digits per axis). */
    public static Optional<String> toMgrs(GeoPosition position, int precisionMetres) {
        GridType type = switch (precisionMetres) {
            case 1 -> GridType.METER;
            case 10 -> GridType.TEN_METER;
            case 100 -> GridType.HUNDRED_METER;
            case 1000 -> GridType.KILOMETER;
            case 10000 -> GridType.TEN_KILOMETER;
            default -> throw new IllegalArgumentException("Precision must be 1, 10, 100, 1000 or 10000 m");
        };
        if (position.latitude() < -80 || position.latitude() > 84) {
            return Optional.empty();
        }
        MGRS mgrs = MGRS.from(Point.point(position.longitude(), position.latitude()));
        return Optional.of(mgrs.coordinate(type));
    }

    /**
     * The 100 km grid square a position lies in, e.g. {@code 32UME} - the cell id of the picture
     * store index and of the aggregation grid (ADR 0013). Polar areas use {@code POLAR-N/S}.
     */
    public static String cell100km(GeoPosition position) {
        if (position.latitude() > 84) {
            return "POLAR-N";
        }
        if (position.latitude() < -80) {
            return "POLAR-S";
        }
        MGRS mgrs = MGRS.from(Point.point(position.longitude(), position.latitude()));
        return mgrs.getZone() + "" + mgrs.getBand() + mgrs.getColumn() + mgrs.getRow();
    }

    /**
     * Parses an MGRS string (spaces optional, case-insensitive) to the south-west corner of the
     * referenced grid square, as MGRS defines it. Returns empty for anything that is not valid MGRS.
     */
    public static Optional<GeoPosition> fromMgrs(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String compact = text.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
        if (compact.length() < 3 || compact.length() > 15 || !MGRS.isMGRS(compact)) {
            return Optional.empty();
        }
        try {
            Point p = MGRS.parse(compact).toPoint();
            return Optional.of(GeoPosition.of(p.getLatitude(), p.getLongitude()));
        } catch (ParseException | RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
