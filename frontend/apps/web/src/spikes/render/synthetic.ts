/**
 * Synthetic contacts for the rendering spike (ROADMAP P0 item 7, NFR-001). Deterministic (seeded)
 * so runs on different machines are comparable. Positions are WGS84 degrees, speeds m/s and
 * courses degrees true - the same units as the real picture (CLAUDE.md principle 5).
 */

/** Small, fast, seedable PRNG (mulberry32); Math.random is not reproducible across runs. */
export function seededRandom(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Mix of sea, air and land symbols in all four standard identities (2525C letter SIDCs). */
const FUNCTION_IDS = [
  'SPCLFF',
  'SPCLDD',
  'SPCLCC',
  'SPCALSM',
  'SPXM',
  'SPCLLL',
  'SPN',
  'SPCU',
  'APMFF',
  'APMHA',
  'APMFC',
  'APCF',
  'APMFQ',
  'APM',
  'APCH',
  'GPUCI',
  'GPUCA',
  'GPEVATL',
  'GPUCR',
  'GPUSM',
  'GPEWM',
  'UPSN',
  'UPSC',
  'UPWM',
];
const IDENTITIES = ['F', 'H', 'N', 'U'];

/**
 * Builds a 15-character letter SIDC: scheme S, identity, then dimension + status + function id
 * (FUNCTION_IDS already start with dimension and status), padded with '-'.
 */
function sidc(identity: string, fn: string): string {
  return `S${identity}${fn}---------------`.slice(0, 15);
}

/** Every SIDC the generator can emit (the atlas pre-renders exactly these). */
export const SYNTHETIC_SIDCS: readonly string[] = IDENTITIES.flatMap((id) =>
  FUNCTION_IDS.map((fn) => sidc(id, fn)),
);

export interface SyntheticContact {
  readonly id: number;
  lat: number;
  lon: number;
  /** m/s */
  speed: number;
  /** degrees true, [0, 360) */
  course: number;
  readonly sidc: string;
  stale: boolean;
}

/** Region the contacts live in: North/Baltic Sea and surroundings, with dense clusters. */
export const REGION = { minLat: 47, maxLat: 60, minLon: -4, maxLon: 24 };

export function generateContacts(count: number, seed = 42): SyntheticContact[] {
  const random = seededRandom(seed);
  const clusters = Array.from({ length: 12 }, () => ({
    lat: REGION.minLat + random() * (REGION.maxLat - REGION.minLat),
    lon: REGION.minLon + random() * (REGION.maxLon - REGION.minLon),
    spread: 0.2 + random() * 1.5,
  }));
  const contacts: SyntheticContact[] = [];
  for (let id = 0; id < count; id++) {
    let lat: number;
    let lon: number;
    if (random() < 0.6) {
      // 60 % in clusters: the dense case that stresses overdraw.
      const c = clusters[Math.floor(random() * clusters.length)] ?? clusters[0];
      if (!c) throw new Error('no clusters');
      lat = c.lat + (random() - 0.5) * c.spread;
      lon = c.lon + (random() - 0.5) * c.spread * 1.6;
    } else {
      lat = REGION.minLat + random() * (REGION.maxLat - REGION.minLat);
      lon = REGION.minLon + random() * (REGION.maxLon - REGION.minLon);
    }
    contacts.push({
      id,
      lat,
      lon,
      speed: 2 + random() * 250,
      course: random() * 360,
      sidc: SYNTHETIC_SIDCS[Math.floor(random() * SYNTHETIC_SIDCS.length)] ?? 'SUZP-----------',
      stale: random() < 0.1,
    });
  }
  return contacts;
}

const METRES_PER_DEGREE_LAT = 111_320;

/**
 * Advances `count` contacts (round-robin from `cursor`) by `dtSeconds` along their course, the
 * way a server delta batch would update a subset of the picture. Returns the updated contacts and
 * the next cursor. Flat-earth step is fine for a rendering benchmark (not for real kinematics).
 */
export function advance(
  contacts: SyntheticContact[],
  cursor: number,
  count: number,
  dtSeconds: number,
): { updated: SyntheticContact[]; cursor: number } {
  const updated: SyntheticContact[] = [];
  const n = contacts.length;
  if (n === 0) return { updated, cursor: 0 };
  for (let i = 0; i < Math.min(count, n); i++) {
    const c = contacts[(cursor + i) % n];
    if (!c) continue;
    const rad = (c.course * Math.PI) / 180;
    const dist = c.speed * dtSeconds;
    c.lat += (dist * Math.cos(rad)) / METRES_PER_DEGREE_LAT;
    c.lon += (dist * Math.sin(rad)) / (METRES_PER_DEGREE_LAT * Math.cos((c.lat * Math.PI) / 180));
    if (
      c.lat < REGION.minLat ||
      c.lat > REGION.maxLat ||
      c.lon < REGION.minLon ||
      c.lon > REGION.maxLon
    ) {
      c.course = (c.course + 180) % 360; // bounce back into the region
    }
    updated.push(c);
  }
  return { updated, cursor: (cursor + count) % n };
}
