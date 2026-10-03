/** Angles: degrees relative to true north, normalised to [0, 360) (CLAUDE.md principle 5). */

export function normalizeBearing(degrees: number): number {
  if (!Number.isFinite(degrees)) throw new RangeError(`Angle must be finite: ${String(degrees)}`);
  const n = ((degrees % 360) + 360) % 360;
  return n >= 360 || Object.is(n, -0) ? 0 : n;
}

/** e.g. `045.0°` (true bearings, MAP-016). `'-'` for missing values. */
export function formatBearing(degrees: number | null | undefined, decimals = 1): string {
  if (degrees === null || degrees === undefined || !Number.isFinite(degrees)) return '-';
  let value = Number(normalizeBearing(degrees).toFixed(decimals));
  if (value >= 360) value = 0; // 359.96 rounded to 1 decimal
  const [int = '0', frac] = value.toFixed(decimals).split('.');
  return `${int.padStart(3, '0')}${frac === undefined ? '' : `.${frac}`}°`;
}
