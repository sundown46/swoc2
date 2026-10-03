import * as mgrsLib from 'mgrs';

import type { LatLon } from './geodesy';
import { isValidLatLon } from './geodesy';

/**
 * Coordinate formatting and parsing (MAP-016/017). Formats: decimal degrees, degrees + decimal
 * minutes, degrees-minutes-seconds and MGRS. The parser is deliberately forgiving because people
 * paste coordinates from everywhere (chat, e-mail, other C2 systems, NMEA): it auto-detects the
 * format, accepts many separators and symbol variants, and explains what it did not understand.
 * It never throws.
 */

export type CoordinateFormat = 'dd' | 'ddm' | 'dms' | 'mgrs';

export interface ParsedCoordinate {
  readonly ok: true;
  readonly position: LatLon;
  /** The format the input was recognised as. */
  readonly format: CoordinateFormat;
}

export interface ParseError {
  readonly ok: false;
  /** Short, user-facing reason (CLAUDE.md "readable messages"). */
  readonly error: string;
}

export type ParseResult = ParsedCoordinate | ParseError;

// --- Formatting ----------------------------------------------------------------------------

/** Default precisions: ~1 m for every format. */
const DEFAULT_DECIMALS: Record<Exclude<CoordinateFormat, 'mgrs'>, number> = {
  dd: 5,
  ddm: 3,
  dms: 1,
};

/**
 * Splits |value| into whole degrees, minutes and seconds at a fixed precision, with correct carry
 * (59.9996' must become the next degree, never `60.000'`). Works on integer units of the last
 * field, so no floating-point rounding can produce 60.
 */
function split(
  value: number,
  parts: 2 | 3,
  decimals: number,
): { deg: number; min: number; sec: number } {
  const scale = 10 ** decimals;
  const unitsPerDegree = (parts === 2 ? 60 : 3600) * scale;
  const total = Math.round(Math.abs(value) * unitsPerDegree);
  const deg = Math.floor(total / unitsPerDegree);
  const rest = total - deg * unitsPerDegree;
  if (parts === 2) return { deg, min: rest / scale, sec: 0 };
  const unitsPerMinute = 60 * scale;
  const min = Math.floor(rest / unitsPerMinute);
  return { deg, min, sec: (rest - min * unitsPerMinute) / scale };
}

function pad(n: number, width: number, decimals: number): string {
  const text = n.toFixed(decimals);
  const [int = '0', frac] = text.split('.');
  return int.padStart(width, '0') + (frac === undefined ? '' : `.${frac}`);
}

function hemisphere(value: number, isLat: boolean): string {
  if (isLat) return value < 0 ? 'S' : 'N';
  return value < 0 ? 'W' : 'E';
}

function formatAxis(
  value: number,
  isLat: boolean,
  format: 'dd' | 'ddm' | 'dms',
  decimals: number,
): string {
  const width = isLat ? 2 : 3;
  const h = hemisphere(value, isLat);
  if (format === 'dd') {
    // Round first so -0.000004 does not print as "0.00000 S".
    const rounded = Number(Math.abs(value).toFixed(decimals));
    const hh = rounded === 0 ? (isLat ? 'N' : 'E') : h;
    return `${pad(rounded, width, decimals)}° ${hh}`;
  }
  if (format === 'ddm') {
    const { deg, min } = split(value, 2, decimals);
    const hh = deg === 0 && min === 0 ? (isLat ? 'N' : 'E') : h;
    return `${pad(deg, width, 0)}°${pad(min, 2, decimals)}′ ${hh}`;
  }
  const { deg, min, sec } = split(value, 3, decimals);
  const hh = deg === 0 && min === 0 && sec === 0 ? (isLat ? 'N' : 'E') : h;
  return `${pad(deg, width, 0)}°${pad(min, 2, 0)}′${pad(sec, 2, decimals)}″ ${hh}`;
}

/**
 * Formats a position, e.g. `53.50000° N 008.10000° E`, `53°30.000′ N 008°06.000′ E`,
 * `53°30′00.0″ N 008°06′00.0″ E`, `32U ME 40301 28270`. Returns `'-'` for invalid input and
 * `'outside MGRS'` for MGRS beyond 80°S / 84°N.
 *
 * @param decimals decimals of the last field (dd/ddm/dms) or MGRS digits per axis (1-5)
 */
export function formatCoordinate(
  position: LatLon | null | undefined,
  format: CoordinateFormat,
  decimals?: number,
): string {
  if (!position || !isValidLatLon(position)) return '-';
  if (format === 'mgrs') return formatMgrs(position, decimals ?? 5);
  const d = decimals ?? DEFAULT_DECIMALS[format];
  return `${formatAxis(position.lat, true, format, d)} ${formatAxis(position.lon, false, format, d)}`;
}

function formatMgrs(position: LatLon, digits: number): string {
  if (position.lat < -80 || position.lat > 84) return 'outside MGRS';
  const compact = mgrsLib.forward(
    [position.lon, position.lat],
    Math.min(5, Math.max(1, Math.round(digits))),
  );
  return spaceMgrs(compact);
}

/** `32UME4030128270` -> `32U ME 40301 28270`. */
export function spaceMgrs(compact: string): string {
  const m = /^(\d{1,2}[A-Z])([A-Z]{2})(\d*)$/.exec(compact);
  if (!m) return compact;
  const [, gzd = '', square = '', digits = ''] = m;
  const half = digits.length / 2;
  return [gzd, square, digits.slice(0, half), digits.slice(half)].filter((s) => s !== '').join(' ');
}

// --- Parsing --------------------------------------------------------------------------------

const MGRS_PATTERN = /^(\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])((?:\d\d){0,5})$/;

function parseMgrs(text: string): ParseResult | null {
  const compact = text.replace(/\s+/g, '').toUpperCase();
  const m = MGRS_PATTERN.exec(compact);
  if (!m) return null;
  const zone = Number(m[1]);
  if (zone < 1 || zone > 60) return { ok: false, error: 'MGRS zone must be 1-60' };
  try {
    // South-west corner of the grid square, like the backend (NGA library) and the MGRS standard.
    const [left, bottom] = mgrsLib.inverse(compact);
    const position = { lat: bottom, lon: left };
    if (!isValidLatLon(position)) return { ok: false, error: 'Not a valid MGRS reference' };
    return { ok: true, position, format: 'mgrs' };
  } catch {
    return { ok: false, error: 'Not a valid MGRS reference' };
  }
}

/** Unifies the many symbols people use for degrees, minutes and seconds. */
function normalizeSymbols(text: string): string {
  return text
    .toUpperCase()
    .replace(/[°º˚∘]/g, ' D ')
    .replace(/(''|[″”"]|SEC|SECONDS?)/g, ' S ')
    .replace(/[′’'`´]/g, ' M ')
    .replace(/\bDEG(REES?)?\b/g, ' D ')
    .replace(/\bMIN(UTES?)?\b/g, ' M ')
    .replace(/\bNORTH\b/g, 'N')
    .replace(/\bSOUTH\b/g, 'S ')
    .replace(/\bEAST\b/g, 'E')
    .replace(/\bWEST\b/g, 'W');
}

type Token =
  | { kind: 'num'; value: number; text: string }
  | { kind: 'hem'; value: 'N' | 'S' | 'E' | 'W' }
  | { kind: 'unit'; value: 'D' | 'M' | 'S' };

/**
 * Decides whether ',' is a decimal separator: only if the text has no '.' at all and contains a
 * comma directly between digits in a part that is separated from the other part by whitespace,
 * semicolon or a hemisphere letter ("53,5 8,1", "53,5N 8,1E"). "53.5,8.1" and "53,8" keep ','
 * as the separator.
 */
function decimalComma(text: string): boolean {
  if (text.includes('.')) return false;
  const commas = (text.match(/\d,\d/g) ?? []).length;
  if (commas === 0) return false;
  const parts = text.split(/[\s;]+|(?<=[NSEW])|(?=[NSEW])/i).filter((p) => /\d/.test(p));
  return parts.length >= 2 && commas >= 1 && !/^\s*-?\d+,-?\d+\s*$/.test(text);
}

function tokenize(raw: string): Token[] | string {
  let text = raw.trim();
  if (decimalComma(text)) text = text.replace(/(\d),(\d)/g, '$1.$2');
  text = normalizeSymbols(text);
  const tokens: Token[] = [];
  // 'S' is emitted as a unit token first; resolveS() decides between seconds and South.
  const re = /([-+]?\d+(?:\.\d+)?)|([NEW])|([DMS])|([\s,;/:|]+)|(.)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m[1] !== undefined) tokens.push({ kind: 'num', value: Number(m[1]), text: m[1] });
    else if (m[2] !== undefined) tokens.push({ kind: 'hem', value: m[2] as 'N' | 'E' | 'W' });
    else if (m[3] !== undefined) tokens.push({ kind: 'unit', value: m[3] as 'D' | 'M' | 'S' });
    else if (m[5] !== undefined) return `Unexpected character "${m[5]}"`;
  }
  return tokens;
}

/**
 * 'S' is ambiguous: seconds marker or South. It is a seconds marker only if it directly follows a
 * number and the current group already had a minutes marker (`30'00"` / `30 M 00 S`); otherwise
 * it is the hemisphere.
 */
function resolveS(tokens: Token[]): Token[] {
  const out: Token[] = [];
  for (const t of tokens) {
    if (t.kind === 'unit' && t.value === 'S') {
      // Look back within the current group for a 'M' marker: then this S closes seconds.
      let sawMinutes = false;
      for (let j = out.length - 1; j >= 0; j--) {
        const p = out[j];
        if (!p || p.kind === 'hem') break;
        if (p.kind === 'unit' && p.value === 'M') {
          sawMinutes = true;
          break;
        }
        if (p.kind === 'unit' && p.value === 'D') break;
      }
      const prev = out[out.length - 1];
      if (sawMinutes && prev?.kind === 'num') {
        out.push(t);
        continue;
      }
      out.push({ kind: 'hem', value: 'S' });
      continue;
    }
    out.push(t);
  }
  return out;
}

interface Axis {
  numbers: number[];
  texts: string[];
  hem?: 'N' | 'S' | 'E' | 'W';
}

/** Splits tokens into the two axes, using hemisphere letters (prefix or suffix style) if present. */
function splitAxes(tokens: Token[]): [Axis, Axis] | string {
  const hems = tokens.filter((t) => t.kind === 'hem');
  const nums = tokens.filter((t): t is Extract<Token, { kind: 'num' }> => t.kind === 'num');
  if (hems.length > 2) return 'Too many hemisphere letters';
  if (nums.length === 0) return 'No numbers found';
  const axes: Axis[] = [];
  if (hems.length === 2) {
    const prefix = tokens[0]?.kind === 'hem';
    let current: Axis = { numbers: [], texts: [] };
    for (const t of tokens) {
      if (t.kind === 'hem') {
        if (prefix) {
          if (current.numbers.length > 0 || current.hem) axes.push(current);
          current = { numbers: [], texts: [], hem: t.value };
        } else {
          current.hem = t.value;
          axes.push(current);
          current = { numbers: [], texts: [] };
        }
      } else if (t.kind === 'num') {
        current.numbers.push(t.value);
        current.texts.push(t.text);
      }
    }
    if (current.numbers.length > 0 || current.hem) axes.push(current);
    const [first, second] = axes;
    if (axes.length !== 2 || !first || !second)
      return 'Could not tell latitude and longitude apart';
    return [first, second];
  }
  if (hems.length === 1) return 'Only one hemisphere letter - give both (e.g. N and E) or none';
  if (nums.length % 2 !== 0 || nums.length > 6)
    return 'Expected two values (latitude and longitude)';
  const half = nums.length / 2;
  return [
    {
      numbers: nums.slice(0, half).map((n) => n.value),
      texts: nums.slice(0, half).map((n) => n.text),
    },
    { numbers: nums.slice(half).map((n) => n.value), texts: nums.slice(half).map((n) => n.text) },
  ];
}

/** Converts one axis to signed decimal degrees, or an error. */
function axisDegrees(axis: Axis, isLat: boolean): number | string {
  const name = isLat ? 'latitude' : 'longitude';
  const max = isLat ? 90 : 180;
  let numbers = [...axis.numbers];
  if (numbers.length === 0 || numbers.length > 3) return `Could not read the ${name}`;
  // Compact NMEA-style ddmm.mmm / dddmm.mmm (e.g. "5330.000N 00806.000E").
  const first = axis.texts[0] ?? '';
  if (
    numbers.length === 1 &&
    axis.hem &&
    /^\d{4,5}(\.\d+)?$/.test(first) &&
    Math.abs(numbers[0] ?? 0) > max
  ) {
    const v = numbers[0] ?? 0;
    const deg = Math.floor(v / 100);
    numbers = [deg, v - deg * 100];
  }
  const negative = numbers.some((n) => n < 0) || axis.texts.some((t) => t.startsWith('-'));
  const [d = 0, m = 0, s = 0] = numbers.map(Math.abs);
  if (numbers.length >= 2 && !Number.isInteger(d))
    return `Degrees of the ${name} must be whole when minutes follow`;
  if (numbers.length === 3 && !Number.isInteger(m))
    return `Minutes of the ${name} must be whole when seconds follow`;
  if (m >= 60) return `Minutes of the ${name} must be below 60`;
  if (s >= 60) return `Seconds of the ${name} must be below 60`;
  let value = d + m / 60 + s / 3600;
  if (value > max) return `The ${name} must be between -${String(max)} and ${String(max)}`;
  const hem = axis.hem;
  if (hem && negative) return `Use either a minus sign or ${isLat ? 'N/S' : 'E/W'}, not both`;
  if (negative || hem === 'S' || hem === 'W') value = -value;
  return value;
}

/**
 * Parses any supported coordinate text. Examples that work: `53.5, 8.1`, `53.5N 8.1E`,
 * `N 53° 30.000' E 008° 06.000'`, `53°30'00"N 8°06'00"E`, `53 30 00 N 8 6 0 E`, `53,5 8,1`,
 * `-33.8568 151.2153`, `E8.1 N53.5`, `5330.000N 00806.000E`, `32U ME 40301 28270`.
 */
export function parseCoordinate(input: string | null | undefined): ParseResult {
  if (input === null || input === undefined || input.trim() === '')
    return { ok: false, error: 'Enter a coordinate' };
  if (input.length > 200) return { ok: false, error: 'Input is too long' };
  const asMgrs = parseMgrs(input);
  if (asMgrs) return asMgrs;
  const tokens = tokenize(input);
  if (typeof tokens === 'string') return { ok: false, error: tokens };
  const axes = splitAxes(resolveS(tokens));
  if (typeof axes === 'string') return { ok: false, error: axes };
  let [a, b] = axes;
  // Longitude given first ("E8.1 N53.5")? Swap based on the hemisphere letters.
  if ((a.hem === 'E' || a.hem === 'W') && (b.hem === 'N' || b.hem === 'S')) [a, b] = [b, a];
  if (a.hem === 'E' || a.hem === 'W' || b.hem === 'N' || b.hem === 'S') {
    return { ok: false, error: 'Give one of N/S and one of E/W' };
  }
  const lat = axisDegrees(a, true);
  if (typeof lat === 'string') return { ok: false, error: lat };
  const lon = axisDegrees(b, false);
  if (typeof lon === 'string') return { ok: false, error: lon };
  const parts = Math.max(a.numbers.length, b.numbers.length);
  const nmea =
    a.numbers.length === 1 && /^\d{4}/.test(a.texts[0] ?? '') && Math.abs(a.numbers[0] ?? 0) > 90;
  const format: CoordinateFormat = nmea || parts === 2 ? 'ddm' : parts === 3 ? 'dms' : 'dd';
  return { ok: true, position: { lat, lon }, format };
}
