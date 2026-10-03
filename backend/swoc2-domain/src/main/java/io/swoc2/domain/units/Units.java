package io.swoc2.domain.units;

/**
 * SI conversion constants and helpers (CLAUDE.md principle 5). The domain stores metres, m/s and
 * degrees only; these exist for adapters (e.g. AIS knots) and must never be used for display -
 * display formatting lives in {@code frontend/packages/units}.
 */
public final class Units {

    private Units() {}

    /** International nautical mile, exact. */
    public static final double METRES_PER_NAUTICAL_MILE = 1852.0;

    /** International foot, exact. */
    public static final double METRES_PER_FOOT = 0.3048;

    /** One knot in m/s, exact (1852 m / 3600 s). */
    public static final double METRES_PER_SECOND_PER_KNOT = 1852.0 / 3600.0;

    public static double knotsToMetresPerSecond(double knots) {
        return knots * METRES_PER_SECOND_PER_KNOT;
    }

    public static double kilometresPerHourToMetresPerSecond(double kmh) {
        return kmh / 3.6;
    }

    public static double feetToMetres(double feet) {
        return feet * METRES_PER_FOOT;
    }

    public static double nauticalMilesToMetres(double nm) {
        return nm * METRES_PER_NAUTICAL_MILE;
    }
}
