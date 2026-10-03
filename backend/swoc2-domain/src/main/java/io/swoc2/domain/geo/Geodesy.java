package io.swoc2.domain.geo;

import io.swoc2.domain.units.Angles;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import net.sf.geographiclib.GeodesicMask;

/**
 * Geodesic calculations on the WGS84 ellipsoid via GeographicLib (ARCHITECTURE §3) - the same
 * library the frontend uses ({@code geographiclib-geodesic}), so range/bearing shown in the UI
 * matches what the server computes. Accurate to nanometres; never use spherical shortcuts.
 */
public final class Geodesy {

    private Geodesy() {}

    /**
     * Range and bearing between two positions.
     *
     * @param distance metres along the geodesic
     * @param initialBearing degrees true at {@code from}, [0, 360)
     * @param finalBearing degrees true at {@code to}, [0, 360)
     */
    public record RangeBearing(double distance, double initialBearing, double finalBearing) {}

    public static RangeBearing inverse(GeoPosition from, GeoPosition to) {
        GeodesicData g = Geodesic.WGS84.Inverse(
                from.latitude(),
                from.longitude(),
                to.latitude(),
                to.longitude(),
                GeodesicMask.DISTANCE | GeodesicMask.AZIMUTH);
        return new RangeBearing(g.s12, Angles.normalize(g.azi1), Angles.normalize(g.azi2));
    }

    /** The position reached from {@code from} after {@code distance} metres on {@code bearing}. */
    public static GeoPosition direct(GeoPosition from, double bearing, double distance) {
        if (!Double.isFinite(bearing) || !Double.isFinite(distance)) {
            throw new IllegalArgumentException("Bearing and distance must be finite");
        }
        GeodesicData g = Geodesic.WGS84.Direct(
                from.latitude(), from.longitude(), bearing, distance, GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE);
        return new GeoPosition(g.lat2, normalizeLongitude(g.lon2), from.altitude());
    }

    /** Wraps a longitude into [-180, 180). */
    static double normalizeLongitude(double longitude) {
        double l = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return l == -180.0 && longitude > 0 ? 180.0 : l;
    }
}
