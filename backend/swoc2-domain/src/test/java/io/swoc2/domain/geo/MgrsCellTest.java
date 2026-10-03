package io.swoc2.domain.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MgrsCellTest {

    @Test
    void cellIsGridZonePlus100kmSquare() {
        assertThat(Mgrs.cell100km(GeoPosition.of(53.5, 8.1))).isEqualTo("32UME");
        assertThat(Mgrs.cell100km(GeoPosition.of(-33.8568, 151.2153))).isEqualTo("56HLH");
        assertThat(Mgrs.cell100km(GeoPosition.of(88, 0))).isEqualTo("POLAR-N");
        assertThat(Mgrs.cell100km(GeoPosition.of(-85, 0))).isEqualTo("POLAR-S");
    }
}
