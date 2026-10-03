package io.swoc2.domain.geo;

/**
 * A WGS84 position in decimal degrees with optional altitude in metres above sea level
 * (CLAUDE.md principle 5, ARCHITECTURE §5.1). Always valid once constructed.
 *
 * @param latitude [-90, 90]
 * @param longitude [-180, 180]
 * @param altitude metres, or null if unknown
 */
public record GeoPosition(double latitude, double longitude, Double altitude) {

    public GeoPosition {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
        if (altitude != null && !Double.isFinite(altitude)) {
            throw new IllegalArgumentException("Altitude must be finite: " + altitude);
        }
    }

    public static GeoPosition of(double latitude, double longitude) {
        return new GeoPosition(latitude, longitude, null);
    }
}
