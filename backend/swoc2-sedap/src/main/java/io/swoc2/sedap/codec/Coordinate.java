package io.swoc2.sedap.codec;

/**
 * One {@code lat,lon[,alt]} element of a GRAPHIC parameter (ICD §6.7).
 *
 * @param latitude WGS84 decimal degrees
 * @param longitude WGS84 decimal degrees
 * @param altitude metres above sea level, or {@code null} when not given
 */
public record Coordinate(double latitude, double longitude, Double altitude) {}
