package io.swoc2.domain.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Vectors on which two independent implementations agree exactly: NGA {@code mil.nga:mgrs} (this
 * side) and proj4js {@code mgrs} (frontend). The same table is in {@code
 * frontend/packages/units/src/mgrs.test.ts}, which keeps backend and frontend in lock-step.
 */
class MgrsTest {

    @ParameterizedTest(name = "{0},{1} -> {2}")
    @CsvSource({
        // lat, lon, expected 1 m MGRS
        "53.5, 8.1, 32UME4030128270",
        "0.0, 0.0, 31NAA6602100000",
        "-33.8568, 151.2153, 56HLH3490052288",
        "38.8977, -77.0365, 18SUJ2339407395",
        // Norway exception: 32V is widened west of 9E between 56N and 64N.
        "60.0, 4.0, 32VKM2128861953",
        // Svalbard exception: zones 31X/33X/35X/37X only.
        "78.0, 15.0, 33XWG0000058369",
        "-79.9, 0.0, 31CDM4129228062",
        "83.9, 0.0, 31XDP6442417856",
        "48.1371, 11.5754, 32UPU9159634746",
    })
    void forwardMatchesReferenceVectors(double lat, double lon, String expected) {
        assertThat(Mgrs.toMgrs(GeoPosition.of(lat, lon), 1)).contains(expected);
    }

    @Test
    void precisionControlsTheDigits() {
        var p = GeoPosition.of(53.5, 8.1);
        assertThat(Mgrs.toMgrs(p, 10000)).contains("32UME42");
        assertThat(Mgrs.toMgrs(p, 1000)).contains("32UME4028");
        assertThat(Mgrs.toMgrs(p, 10)).contains("32UME40302827");
    }

    @Test
    void polarAreasAreNotMgrs() {
        assertThat(Mgrs.toMgrs(GeoPosition.of(85, 0), 1)).isEmpty();
        assertThat(Mgrs.toMgrs(GeoPosition.of(-81, 0), 1)).isEmpty();
    }

    @Test
    void parsingToleratesSpacesAndCase() {
        var p = Mgrs.fromMgrs(" 32u me 40301 28270 ").orElseThrow();
        assertThat(p.latitude()).isCloseTo(53.5, within(1e-4));
        assertThat(p.longitude()).isCloseTo(8.1, within(1e-4));
    }

    @Test
    void roundTripIsWithinTheGridSquare() {
        var p = GeoPosition.of(48.1371, 11.5754);
        var back = Mgrs.fromMgrs(Mgrs.toMgrs(p, 1).orElseThrow()).orElseThrow();
        assertThat(Geodesy.inverse(p, back).distance()).isLessThan(1.5);
    }

    @Test
    void garbageIsEmptyNotAnException() {
        assertThat(Mgrs.fromMgrs("hello")).isEmpty();
        assertThat(Mgrs.fromMgrs("99ZZZ1234")).isEmpty();
        assertThat(Mgrs.fromMgrs("")).isEmpty();
        assertThat(Mgrs.fromMgrs(null)).isEmpty();
        assertThat(Mgrs.fromMgrs("32UMD065552886")).isEmpty(); // odd digit count
    }
}
