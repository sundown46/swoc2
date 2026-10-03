package io.swoc2.domain.units;

/** Angle helpers: degrees relative to true north, normalised to [0, 360) (CLAUDE.md principle 5). */
public final class Angles {

    private Angles() {}

    /**
     * Normalises any finite angle to [0, 360). {@code -0.0} and values that round to 360 become
     * {@code 0.0}, so equal directions compare equal.
     *
     * @throws IllegalArgumentException for NaN or infinite input
     */
    public static double normalize(double degrees) {
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("Angle must be finite: " + degrees);
        }
        double n = degrees % 360.0;
        if (n < 0) {
            n += 360.0;
        }
        return n >= 360.0 || n == 0.0 ? 0.0 : n;
    }
}
