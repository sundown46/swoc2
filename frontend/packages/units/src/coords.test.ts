import { describe, expect, it } from 'vitest';

import { formatCoordinate, parseCoordinate, spaceMgrs, type CoordinateFormat } from './coords';

const close = (a: number, b: number, eps = 1e-6) => Math.abs(a - b) < eps;

function expectParsed(
  text: string,
  lat: number,
  lon: number,
  format?: CoordinateFormat,
  eps = 1e-6,
) {
  const r = parseCoordinate(text);
  if (!r.ok) throw new Error(`"${text}" failed: ${r.error}`);
  expect(close(r.position.lat, lat, eps), `${text} lat ${String(r.position.lat)}`).toBe(true);
  expect(close(r.position.lon, lon, eps), `${text} lon ${String(r.position.lon)}`).toBe(true);
  if (format) expect(r.format).toBe(format);
}

describe('formatCoordinate', () => {
  const p = { lat: 53.5, lon: 8.1 };

  it('formats all four formats', () => {
    expect(formatCoordinate(p, 'dd')).toBe('53.50000° N 008.10000° E');
    expect(formatCoordinate(p, 'ddm')).toBe('53°30.000′ N 008°06.000′ E');
    expect(formatCoordinate(p, 'dms')).toBe('53°30′00.0″ N 008°06′00.0″ E');
    expect(formatCoordinate(p, 'mgrs')).toBe('32U ME 40301 28270');
  });

  it('uses S and W for negative values', () => {
    expect(formatCoordinate({ lat: -33.8568, lon: -70.5 }, 'dd', 4)).toBe('33.8568° S 070.5000° W');
  });

  it('carries rounding into the next unit instead of printing 60', () => {
    expect(formatCoordinate({ lat: 53.99999999, lon: 8.99999999 }, 'ddm')).toBe(
      '54°00.000′ N 009°00.000′ E',
    );
    expect(formatCoordinate({ lat: 10.99999999, lon: 0 }, 'dms')).toBe(
      '11°00′00.0″ N 000°00′00.0″ E',
    );
  });

  it('never prints a negative zero hemisphere', () => {
    expect(formatCoordinate({ lat: -0.000001, lon: -0.000001 }, 'dd')).toBe(
      '00.00000° N 000.00000° E',
    );
  });

  it('degrades on bad input', () => {
    expect(formatCoordinate(null, 'dd')).toBe('-');
    expect(formatCoordinate({ lat: 91, lon: 0 }, 'dd')).toBe('-');
    expect(formatCoordinate({ lat: 85, lon: 0 }, 'mgrs')).toBe('outside MGRS');
  });

  it('spaces MGRS by precision', () => {
    expect(spaceMgrs('32UME4030128270')).toBe('32U ME 40301 28270');
    expect(spaceMgrs('32UME42')).toBe('32U ME 4 2');
    expect(formatCoordinate({ lat: 53.5, lon: 8.1 }, 'mgrs', 3)).toBe('32U ME 403 282');
  });
});

describe('parseCoordinate - accepted inputs', () => {
  it.each([
    ['53.5, 8.1', 'dd'],
    ['53.5 8.1', 'dd'],
    ['53.5;8.1', 'dd'],
    ['53.5,8.1', 'dd'],
    ['53.5N 8.1E', 'dd'],
    ['53.5 N, 8.1 E', 'dd'],
    ['N53.5 E8.1', 'dd'],
    ['N 53.5° E 8.1°', 'dd'],
    ['53.5°N 8.1°E', 'dd'],
    ['E8.1 N53.5', 'dd'],
    ['8.1E 53.5N', 'dd'],
    ['53,5 8,1', 'dd'],
    ['53,5N 8,1E', 'dd'],
    ['  53.5 north 8.1 east ', 'dd'],
    ['53°30.000′ N 008°06.000′ E', 'ddm'],
    ["N 53° 30.000' E 008° 06.000'", 'ddm'],
    ['53 30.0 N 8 6.0 E', 'ddm'],
    ['5330.000N 00806.000E', 'ddm'],
    ['53°30′00.0″ N 008°06′00.0″ E', 'dms'],
    ['53°30\'00"N 8°06\'00"E', 'dms'],
    ['53 30 00 N 8 6 0 E', 'dms'],
    ['53d30m00s N 8d6m0s E', 'dms'],
    ['53 deg 30 min 0 sec N, 8 deg 6 min 0 sec E', 'dms'],
  ])('%s', (text, format) => {
    expectParsed(text, 53.5, 8.1, format as CoordinateFormat);
  });

  it('handles the southern and western hemispheres and minus signs', () => {
    expectParsed('-33.8568 151.2153', -33.8568, 151.2153, 'dd');
    expectParsed('33.8568 S 151.2153 E', -33.8568, 151.2153, 'dd');
    expectParsed(
      'S 33 51 24.5 E 151 12 55.1',
      -(33 + 51 / 60 + 24.5 / 3600),
      151 + 12 / 60 + 55.1 / 3600,
      'dms',
    );
    expectParsed(
      '33°51\'24.5"S 151°12\'55.1"E',
      -(33 + 51 / 60 + 24.5 / 3600),
      151 + 12 / 60 + 55.1 / 3600,
      'dms',
    );
    expectParsed('38.8977 N 77.0365 W', 38.8977, -77.0365, 'dd');
    expectParsed('38.8977, -77.0365', 38.8977, -77.0365, 'dd');
  });

  it('parses MGRS with or without spaces (south-west corner, like the backend)', () => {
    expectParsed('32U ME 40301 28270', 53.5, 8.1, 'mgrs', 2e-5);
    expectParsed('32ume4030128270', 53.5, 8.1, 'mgrs', 2e-5);
  });

  it('round-trips every output format', () => {
    const points = [
      { lat: 53.5, lon: 8.1 },
      { lat: -33.8568, lon: 151.2153 },
      { lat: 0.000001, lon: -179.999999 },
      { lat: 78.0, lon: 15.0 },
    ];
    for (const p of points) {
      for (const f of ['dd', 'ddm', 'dms'] as const) {
        const text = formatCoordinate(p, f);
        expectParsed(text, p.lat, p.lon, f, 2e-5);
      }
    }
  });
});

describe('parseCoordinate - rejected inputs explain why', () => {
  it.each([
    ['', 'Enter a coordinate'],
    ['hello', 'Unexpected character'],
    ['53.5', 'two values'],
    ['95 8', 'latitude must be between'],
    ['53 190', 'longitude must be between'],
    ['53 61.0 N 8 6 E', 'Minutes'],
    ['53 30 61 N 8 6 0 E', 'Seconds'],
    ['53.5 N 8.1 N', 'one of N/S and one of E/W'],
    ['53.5N 8.1', 'Only one hemisphere'],
    ['-53.5 S 8.1 E', 'minus sign or N/S'],
    ['53.5 8.1 #', 'Unexpected character'],
    ['53.5 30.2 N 8.1 6 E', 'whole'],
    ['99ZZZ', 'Unexpected character'],
  ])('%s', (text, fragment) => {
    const r = parseCoordinate(text);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toContain(fragment);
  });

  it('never throws, whatever comes in', () => {
    const junk = [
      '∞',
      '1e999 1e999',
      '----',
      '°°°',
      'NSEW',
      '1 2 3 4 5 6 7',
      'S S S',
      '0x1F 3',
      '\u0000',
      'a'.repeat(500),
    ];
    for (const j of junk) expect(() => parseCoordinate(j)).not.toThrow();
  });
});
