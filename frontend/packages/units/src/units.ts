/**
 * Unit conversion and formatting for display (MAP-016). Inputs are always SI (metres, m/s), as
 * stored everywhere else (CLAUDE.md principle 5); this is the only place values are converted -
 * never inline in components.
 */

export type DistanceUnit = 'm' | 'km' | 'nm' | 'ft';
export type SpeedUnit = 'm/s' | 'km/h' | 'kn';
export type AltitudeUnit = 'm' | 'ft';

/** Exact definitions: international nautical mile and foot. */
export const METRES_PER_NAUTICAL_MILE = 1852;
export const METRES_PER_FOOT = 0.3048;
const METRES_PER_KILOMETRE = 1000;

export function metresTo(unit: DistanceUnit | AltitudeUnit, metres: number): number {
  switch (unit) {
    case 'm':
      return metres;
    case 'km':
      return metres / METRES_PER_KILOMETRE;
    case 'nm':
      return metres / METRES_PER_NAUTICAL_MILE;
    case 'ft':
      return metres / METRES_PER_FOOT;
  }
}

export function toMetres(unit: DistanceUnit | AltitudeUnit, value: number): number {
  switch (unit) {
    case 'm':
      return value;
    case 'km':
      return value * METRES_PER_KILOMETRE;
    case 'nm':
      return value * METRES_PER_NAUTICAL_MILE;
    case 'ft':
      return value * METRES_PER_FOOT;
  }
}

export function metresPerSecondTo(unit: SpeedUnit, mps: number): number {
  switch (unit) {
    case 'm/s':
      return mps;
    case 'km/h':
      return mps * 3.6;
    case 'kn':
      return (mps * 3600) / METRES_PER_NAUTICAL_MILE;
  }
}

export function toMetresPerSecond(unit: SpeedUnit, value: number): number {
  switch (unit) {
    case 'm/s':
      return value;
    case 'km/h':
      return value / 3.6;
    case 'kn':
      return (value * METRES_PER_NAUTICAL_MILE) / 3600;
  }
}

const DISTANCE_LABEL: Record<DistanceUnit, string> = { m: 'm', km: 'km', nm: 'NM', ft: 'ft' };

/** Fixed decimals, never `-0`, `'-'` for missing values. */
export function fixed(value: number | null | undefined, decimals: number): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-';
  const text = value.toFixed(decimals);
  return /^-0(\.0+)?$/.test(text) ? text.slice(1) : text;
}

/** e.g. `12.35 NM`. Default decimals: m/ft 0, km/NM 2. */
export function formatDistance(
  metres: number | null | undefined,
  unit: DistanceUnit,
  decimals?: number,
): string {
  if (metres === null || metres === undefined || !Number.isFinite(metres)) return '-';
  const d = decimals ?? (unit === 'm' || unit === 'ft' ? 0 : 2);
  return `${fixed(metresTo(unit, metres), d)} ${DISTANCE_LABEL[unit]}`;
}

/** e.g. `14.2 kn`. */
export function formatSpeed(mps: number | null | undefined, unit: SpeedUnit, decimals = 1): string {
  if (mps === null || mps === undefined || !Number.isFinite(mps)) return '-';
  return `${fixed(metresPerSecondTo(unit, mps), decimals)} ${unit}`;
}

/** e.g. `1200 ft`. */
export function formatAltitude(
  metres: number | null | undefined,
  unit: AltitudeUnit,
  decimals = 0,
): string {
  if (metres === null || metres === undefined || !Number.isFinite(metres)) return '-';
  return `${fixed(metresTo(unit, metres), decimals)} ${unit}`;
}
