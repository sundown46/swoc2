package io.swoc2.sedap.codec;

import io.swoc2.sedap.schema.FieldSpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Produces a valid, non-trivial value for any field spec, for generated round-trip tests. */
final class SampleValues {

    private SampleValues() {}

    static Object valueFor(FieldSpec spec, int seed) {
        double fraction = 0.123 + (seed % 7) * 0.1;
        return switch (spec.kind()) {
            case TEXT ->
                spec.options().isEmpty()
                        ? "T" + seed + "-" + spec.name().replaceAll("[^A-Za-z0-9]", "")
                        : spec.options().getFirst();
            case HEX -> spec.maxBytes() != null && spec.maxBytes() <= 2 ? "%02X".formatted(seed % 0x80) : "A1B2";
            case SIDC -> "SFSPCLFF-------";
            case SOURCE_CHARS -> "AR";
            case ENCODING -> "NONE";
            case LATITUDE -> 53.5 + fraction;
            case LONGITUDE -> -8.25 - fraction;
            case ANGLE -> 12.5 + seed;
            case DOUBLE -> inRange(spec, 42.25 + seed);
            case INTEGER, CODE ->
                spec.codes().isEmpty()
                        ? (int) Math.max(spec.min() == null ? 1 : spec.min(), 1)
                        : spec.codes().keySet().stream()
                                .sorted()
                                .skip(seed % spec.codes().size())
                                .findFirst()
                                .orElseThrow();
            case HEX_TIME -> Instant.parse("2026-10-03T01:02:03.456Z").plusSeconds(seed);
            case BOOLEAN -> seed % 2 == 0;
            case ON_OFF -> seed % 2 == 1;
            case BASE64 -> Base64Data.of(("payload " + seed + " äöü;#\n").getBytes(StandardCharsets.UTF_8));
            case DOUBLE_LIST -> List.of(8725000.5, 8735000.0 + seed);
            case LEVEL_LIST -> List.of(new Level("Accu1", 50.5), new Level("Accu2", seed % 100));
            case COORDINATE -> new Coordinate(54.23, 12.86, 10.5);
            case COORDINATE_LIST -> List.of(new Coordinate(54.23, 12.86, null), new Coordinate(54.3, 12.9, 100.0));
            case TEXT_LIST -> List.of("ORKA", "DRONE" + seed);
        };
    }

    private static double inRange(FieldSpec spec, double preferred) {
        double value = preferred;
        if (spec.max() != null && value > spec.max()) {
            value = spec.max();
        }
        if (spec.min() != null && value < spec.min()) {
            value = spec.min();
        }
        return value;
    }
}
