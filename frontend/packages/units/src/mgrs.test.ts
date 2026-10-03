import { describe, expect, it } from 'vitest';

import { formatCoordinate, parseCoordinate } from './coords';

/**
 * Same table as backend `MgrsTest`: values on which NGA mil.nga:mgrs (Java) and proj4js mgrs (here)
 * agree exactly, incl. the Norway (32V) and Svalbard (33X) zone exceptions.
 */
const VECTORS: [number, number, string][] = [
  [53.5, 8.1, '32UME4030128270'],
  [0.0, 0.0, '31NAA6602100000'],
  [-33.8568, 151.2153, '56HLH3490052288'],
  [38.8977, -77.0365, '18SUJ2339407395'],
  [60.0, 4.0, '32VKM2128861953'],
  [78.0, 15.0, '33XWG0000058369'],
  [-79.9, 0.0, '31CDM4129228062'],
  [83.9, 0.0, '31XDP6442417856'],
  [48.1371, 11.5754, '32UPU9159634746'],
];

describe('MGRS parity with the backend', () => {
  it.each(VECTORS)('%f, %f -> %s', (lat, lon, expected) => {
    expect(formatCoordinate({ lat, lon }, 'mgrs').replace(/ /g, '')).toBe(expected);
    const back = parseCoordinate(expected);
    expect(back.ok).toBe(true);
  });
});
