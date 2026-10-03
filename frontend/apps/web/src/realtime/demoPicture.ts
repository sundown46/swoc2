import type { Envelope } from './protocol';

/** Minimal state of the spike's `demo` topic: contacts by id (docs/realtime-protocol.md §10). */
export interface DemoContact {
  readonly id: number;
  readonly lat: number;
  readonly lon: number;
}

/**
 * Applies `demo` snapshots and deltas to a map. Pure, so the ordering logic around it can be
 * tested; invalid items are skipped (they come from the network).
 */
export function applyDemoEnvelope(state: Map<number, DemoContact>, envelope: Envelope): void {
  if (envelope.payload.topic !== 'demo') return;
  const toContact = (item: unknown): DemoContact | null => {
    if (typeof item !== 'object' || item === null) return null;
    const { id, lat, lon } = item as Record<string, unknown>;
    return typeof id === 'number' && typeof lat === 'number' && typeof lon === 'number'
      ? { id, lat, lon }
      : null;
  };
  if (envelope.type === 'snapshot') {
    state.clear();
    const items = Array.isArray(envelope.payload.items)
      ? (envelope.payload.items as unknown[])
      : [];
    for (const item of items) {
      const c = toContact(item);
      if (c) state.set(c.id, c);
    }
  } else if (envelope.type === 'delta') {
    const upserts = Array.isArray(envelope.payload.upserts)
      ? (envelope.payload.upserts as unknown[])
      : [];
    for (const item of upserts) {
      const c = toContact(item);
      if (c) state.set(c.id, c);
    }
    const removes = Array.isArray(envelope.payload.removes)
      ? (envelope.payload.removes as unknown[])
      : [];
    for (const id of removes) {
      if (typeof id === 'number') state.delete(id);
    }
  }
}
