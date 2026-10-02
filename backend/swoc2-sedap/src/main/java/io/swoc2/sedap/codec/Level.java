package io.swoc2.sedap.codec;

/**
 * One {@code name#percent} entry of a STATUS level list (ICD §6.9), e.g. battery {@code Accu1}
 * at 50 %.
 */
public record Level(String name, double percent) {}
