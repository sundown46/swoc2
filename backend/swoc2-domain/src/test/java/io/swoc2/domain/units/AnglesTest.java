package io.swoc2.domain.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnglesTest {

    @ParameterizedTest
    @CsvSource({"0, 0", "359.5, 359.5", "360, 0", "-1, 359", "720.25, 0.25", "-360, 0", "-0.0, 0"})
    void normalizesToZeroInclusive360Exclusive(double in, double expected) {
        assertThat(Angles.normalize(in)).isCloseTo(expected, within(1e-12));
    }

    @Test
    void tinyNegativeDoesNotBecome360() {
        assertThat(Angles.normalize(-1e-14)).isLessThan(360.0);
    }

    @Test
    void rejectsNonFinite() {
        assertThatThrownBy(() -> Angles.normalize(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactUnitConstants() {
        assertThat(Units.knotsToMetresPerSecond(1)).isEqualTo(1852.0 / 3600.0);
        assertThat(Units.feetToMetres(1000)).isCloseTo(304.8, within(1e-12));
        assertThat(Units.nauticalMilesToMetres(2)).isEqualTo(3704.0);
        assertThat(Units.kilometresPerHourToMetresPerSecond(36)).isCloseTo(10.0, within(1e-12));
    }
}
