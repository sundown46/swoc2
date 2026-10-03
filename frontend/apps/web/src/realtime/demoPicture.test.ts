import { describe, expect, it } from 'vitest';

import { applyDemoEnvelope, type DemoContact } from './demoPicture';

describe('applyDemoEnvelope', () => {
  it('replaces on snapshot, merges deltas, skips garbage', () => {
    const state = new Map<number, DemoContact>();
    applyDemoEnvelope(state, {
      v: 1,
      seq: 2,
      type: 'snapshot',
      payload: { topic: 'demo', items: [{ id: 1, lat: 1, lon: 2 }, { id: 'x' }, null] },
    });
    applyDemoEnvelope(state, {
      v: 1,
      seq: 3,
      type: 'delta',
      payload: { topic: 'demo', upserts: [{ id: 2, lat: 3, lon: 4 }], removes: [1, 'y'] },
    });
    applyDemoEnvelope(state, {
      v: 1,
      seq: 4,
      type: 'delta',
      payload: { topic: 'other', upserts: [{ id: 9, lat: 0, lon: 0 }] },
    });

    expect([...state.values()]).toEqual([{ id: 2, lat: 3, lon: 4 }]);
  });
});
