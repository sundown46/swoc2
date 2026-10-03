import { Geodesic } from 'geographiclib-geodesic';

import { normalizeBearing } from './angles';

/**
 * WGS84 geodesics via GeographicLib - the same library as the backend (`Geodesy.java`), so range
 * and bearing in the UI match the server (ARCHITECTURE §3). Never use spherical shortcuts.
 */

export interface LatLon {
  readonly lat: number;
  readonly lon: number;
}

export interface RangeBearing {
  /** metres */
  readonly distance: number;
  /** degrees true at the start, [0, 360) */
  readonly initialBearing: number;
  /** degrees true at the end, [0, 360) */
  readonly finalBearing: number;
}

export function isValidLatLon(p: LatLon): boolean {
  return (
    Number.isFinite(p.lat) &&
    Number.isFinite(p.lon) &&
    Math.abs(p.lat) <= 90 &&
    Math.abs(p.lon) <= 180
  );
}

export function inverse(from: LatLon, to: LatLon): RangeBearing {
  if (!isValidLatLon(from) || !isValidLatLon(to)) throw new RangeError('Invalid position');
  const r = Geodesic.WGS84.Inverse(from.lat, from.lon, to.lat, to.lon);
  return {
    distance: r.s12 ?? 0,
    initialBearing: normalizeBearing(r.azi1 ?? 0),
    finalBearing: normalizeBearing(r.azi2 ?? 0),
  };
}

export function direct(from: LatLon, bearing: number, distance: number): LatLon {
  if (!isValidLatLon(from) || !Number.isFinite(bearing) || !Number.isFinite(distance)) {
    throw new RangeError('Invalid input');
  }
  const r = Geodesic.WGS84.Direct(from.lat, from.lon, bearing, distance);
  const lon = (((((r.lon2 ?? 0) + 180) % 360) + 360) % 360) - 180;
  return { lat: r.lat2 ?? 0, lon };
}
