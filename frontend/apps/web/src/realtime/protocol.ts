import { z } from 'zod';

/**
 * Wire types of the realtime protocol (docs/realtime-protocol.md §3-§5). Every server envelope is
 * validated with zod at the boundary; anything that fails is dropped and logged, never thrown into
 * the app (CLAUDE.md principle 1).
 */

export const PROTOCOL_VERSION = 1;

export const envelopeSchema = z.object({
  v: z.literal(PROTOCOL_VERSION),
  seq: z.number().int().nonnegative(),
  type: z.string().min(1).max(64),
  payload: z.record(z.string(), z.unknown()),
});

export type Envelope = z.infer<typeof envelopeSchema>;

export const sessionResponseSchema = z.object({
  sessionId: z.string().min(1).max(128),
  heartbeatMs: z.number().int().positive(),
  pollHoldMs: z.number().int().positive(),
});

export type SessionInfo = z.infer<typeof sessionResponseSchema>;

export type ClientMessageType = 'subscribe' | 'unsubscribe' | 'resync' | 'ping';

export interface ClientMessage {
  readonly v: typeof PROTOCOL_VERSION;
  readonly type: ClientMessageType;
  readonly payload: Record<string, unknown>;
}

export function clientMessage(type: ClientMessageType, topic?: string): ClientMessage {
  return { v: PROTOCOL_VERSION, type, payload: topic === undefined ? {} : { topic } };
}

/** Parses one envelope; returns null (and logs) if invalid. */
export function parseEnvelope(raw: unknown): Envelope | null {
  let value = raw;
  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw) as unknown;
    } catch {
      console.warn('Realtime: dropping non-JSON message');
      return null;
    }
  }
  const result = envelopeSchema.safeParse(value);
  if (!result.success) {
    console.warn('Realtime: dropping invalid envelope', result.error.issues[0]?.message);
    return null;
  }
  return result.data;
}

/** WS close codes of docs/realtime-protocol.md §9. */
export const CloseCode = {
  invalidRequest: 4400,
  forbidden: 4403,
  unknownSession: 4404,
  replaced: 4409,
} as const;
