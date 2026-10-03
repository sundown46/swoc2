import { describe, expect, it } from 'vitest';

import { formatBearing, normalizeBearing } from './angles';
import { direct, inverse } from './geodesy';
import {
  formatAltitude,
  formatDistance,
  formatSpeed,
  metresPerSecondTo,
  metresTo,
  toMetres,
  toMetresPerSecond,
} from './units';

describe('units', () => {
  it('converts with exact definitions', () => {
    expect(metresTo('nm', 1852)).toBe(1);
    expect(metresTo('ft', 0.3048)).toBe(1);
    expect(metresTo('km', 1500)).toBe(1.5);
    expect(metresPerSecondTo('kn', 1852 / 3600)).toBeCloseTo(1, 12);
    expect(metresPerSecondTo('km/h', 10)).toBeCloseTo(36, 12);
    for (const u of ['m', 'km', 'nm', 'ft'] as const)
      expect(toMetres(u, metresTo(u, 1234.5))).toBeCloseTo(1234.5, 9);
    for (const u of ['m/s', 'km/h', 'kn'] as const)
      expect(toMetresPerSecond(u, metresPerSecondTo(u, 7.7))).toBeCloseTo(7.7, 12);
  });

  it('formats for display, with "-" for missing values', () => {
    expect(formatDistance(22224, 'nm')).toBe('12.00 NM');
    expect(formatDistance(1500, 'km')).toBe('1.50 km');
    expect(formatDistance(12.4, 'm')).toBe('12 m');
    expect(formatSpeed(10.2889, 'kn')).toBe('20.0 kn');
    expect(formatAltitude(304.8, 'ft')).toBe('1000 ft');
    expect(formatDistance(null, 'm')).toBe('-');
    expect(formatSpeed(Number.NaN, 'kn')).toBe('-');
    expect(formatAltitude(-0.0001, 'm')).toBe('0 m');
  });
});

describe('angles', () => {
  it('normalises bearings to [0, 360)', () => {
    expect(normalizeBearing(360)).toBe(0);
    expect(normalizeBearing(-90)).toBe(270);
    expect(normalizeBearing(725)).toBe(5);
    expect(Object.is(normalizeBearing(-0), 0)).toBe(true);
    expect(() => normalizeBearing(Number.NaN)).toThrow();
  });

  it('formats true bearings with three integer digits', () => {
    expect(formatBearing(45)).toBe('045.0°');
    expect(formatBearing(359.96)).toBe('000.0°');
    expect(formatBearing(7.25, 0)).toBe('007°');
    expect(formatBearing(undefined)).toBe('-');
  });
});

describe('geodesy (same reference vectors as the backend GeodesyTest)', () => {
  it('JFK to LHR, GeographicLib GeodSolve manual example', () => {
    const r = inverse({ lat: 40.6, lon: -73.8 }, { lat: 51.6, lon: -0.5 });
    expect(r.distance).toBeCloseTo(5551759.400319, 5);
    expect(r.initialBearing).toBeCloseTo(51.198882845579, 9);
    expect(r.finalBearing).toBeCloseTo(107.821776735514, 9);
  });

  it('direct inverts inverse and wraps the dateline', () => {
    const from = { lat: 53.5, lon: 8.1 };
    const to = direct(from, 45, 10000);
    expect(inverse(from, to).distance).toBeCloseTo(10000, 6);
    expect(direct({ lat: 0, lon: 179.9 }, 90, 50000).lon).toBeLessThan(-179);
  });

  it('rejects invalid positions', () => {
    expect(() => inverse({ lat: 91, lon: 0 }, { lat: 0, lon: 0 })).toThrow(RangeError);
  });
});
