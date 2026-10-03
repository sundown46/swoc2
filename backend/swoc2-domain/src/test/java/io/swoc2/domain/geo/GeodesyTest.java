package io.swoc2.domain.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Reference values from GeographicLib's documented examples and independent sources. The same
 * vectors are checked in {@code frontend/packages/units/src/geodesy.test.ts}, so both sides agree.
 */
class GeodesyTest {

    @Test
    void jfkToLondonHeathrow() {
        // GeographicLib GeodSolve manual example: JFK (40.6, -73.8) to LHR (51.6, -0.5).
        var rb = Geodesy.inverse(GeoPosition.of(40.6, -73.8), GeoPosition.of(51.6, -0.5));
        assertThat(rb.distance()).isCloseTo(5_551_759.400319, within(1e-5));
        assertThat(rb.initialBearing()).isCloseTo(51.198882845579, within(1e-6));
        assertThat(rb.finalBearing()).isCloseTo(107.821776735514, within(1e-6));
    }

    @Test
    void bearingsAreNormalisedAndWestwardWorks() {
        var rb = Geodesy.inverse(GeoPosition.of(53.5, 8.1), GeoPosition.of(53.5, 7.1));
        assertThat(rb.initialBearing()).isBetween(269.0, 271.0);
        assertThat(rb.distance()).isCloseTo(66_334.0, within(50.0));
    }

    @Test
    void samePointIsZeroDistance() {
        var p = GeoPosition.of(53.5, 8.1);
        assertThat(Geodesy.inverse(p, p).distance()).isZero();
    }

    @Test
    void directInvertsInverse() {
        var from = GeoPosition.of(53.5, 8.1);
        var to = Geodesy.direct(from, 45.0, 10_000.0);
        var rb = Geodesy.inverse(from, to);
        assertThat(rb.distance()).isCloseTo(10_000.0, within(1e-6));
        assertThat(rb.initialBearing()).isCloseTo(45.0, within(1e-9));
    }

    @Test
    void directAcrossTheDatelineWrapsLongitude() {
        var to = Geodesy.direct(GeoPosition.of(0, 179.9), 90.0, 50_000.0);
        assertThat(to.longitude()).isBetween(-180.0, -179.0);
    }

    @Test
    void invalidPositionsAreRejected() {
        assertThatThrownBy(() -> GeoPosition.of(91, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeoPosition.of(0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPosition(0, 0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
