import { describe, expect, it } from 'vitest';

import { frameStats } from './frameStats';
import { advance, generateContacts, REGION, SYNTHETIC_SIDCS } from './synthetic';

describe('synthetic contacts', () => {
  it('is deterministic for a seed', () => {
    expect(generateContacts(100, 7)).toEqual(generateContacts(100, 7));
    expect(generateContacts(100, 7)).not.toEqual(generateContacts(100, 8));
  });

  it('stays in the region and uses valid 15-char SIDCs', () => {
    for (const c of generateContacts(2000)) {
      expect(c.lat).toBeGreaterThanOrEqual(REGION.minLat - 1);
      expect(c.lat).toBeLessThanOrEqual(REGION.maxLat + 1);
      expect(c.course).toBeGreaterThanOrEqual(0);
      expect(c.course).toBeLessThan(360);
      expect(c.sidc).toHaveLength(15);
    }
    expect(new Set(SYNTHETIC_SIDCS).size).toBe(SYNTHETIC_SIDCS.length);
  });

  it('advances a round-robin subset', () => {
    const contacts = generateContacts(10);
    const before = contacts.map((c) => c.lat);
    const { updated, cursor } = advance(contacts, 8, 4, 10);
    expect(updated.map((c) => c.id)).toEqual([8, 9, 0, 1]);
    expect(cursor).toBe(2);
    expect(contacts[5]?.lat).toBe(before[5]);
    expect(contacts[8]?.lat).not.toBe(before[8]);
  });
});

describe('frameStats', () => {
  it('summarises frame intervals', () => {
    const intervals = [...Array<number>(99).fill(16), 100];
    const stats = frameStats(intervals);
    expect(stats.frames).toBe(100);
    expect(stats.avgFps).toBeCloseTo(100_000 / (99 * 16 + 100), 0);
    expect(stats.onePercentLowFps).toBe(10);
    expect(stats.maxFrameMs).toBe(100);
    expect(stats.longFrames).toBe(1);
  });

  it('handles an empty run', () => {
    expect(frameStats([]).avgFps).toBe(0);
  });
});
