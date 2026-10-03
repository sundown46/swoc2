import { clientMessage, sessionResponseSchema, type Envelope, type SessionInfo } from './protocol';
import {
  browserTransportEnv,
  csrfToken,
  LongPollTransport,
  SseTransport,
  WebSocketTransport,
  type Transport,
  type TransportEnv,
  type TransportKind,
} from './transports';

/**
 * Realtime client (docs/realtime-protocol.md §6-§8): creates the session, negotiates the best
 * working transport (WebSocket -> SSE -> long-polling), falls back on failure, periodically tries
 * to upgrade again, enforces seq order, detects gaps and resyncs, and survives transport switches
 * without losing envelopes (re-attach with the last seq).
 *
 * Framework-free on purpose (CLAUDE.md: no business logic in components); React hooks and the
 * plugin SDK wrap it.
 */

export const TRANSPORT_ORDER: readonly TransportKind[] = ['websocket', 'sse', 'long-poll'];

export type ConnectionStatus = 'connecting' | 'connected' | 'offline';

export interface RealtimeState {
  readonly status: ConnectionStatus;
  readonly transport: TransportKind | null;
  readonly sessionId: string | null;
  readonly lastSeq: number;
  readonly received: number;
  readonly gaps: number;
  readonly resyncs: number;
  readonly fallbacks: number;
  readonly lastError: string | null;
}

export interface RealtimeClientOptions {
  /** Instance base URL (`{base}/`), derived from the page or config.json. */
  readonly base: URL;
  /** Force one transport (Settings -> Realtime); disables fallback and upgrades. */
  readonly forcedTransport?: TransportKind;
  readonly env?: TransportEnv;
  /** First-contact timeout per transport attempt (§8: 5 s). */
  readonly connectTimeoutMs?: number;
  /** Background upgrade attempt interval while degraded (§8: 60 s). */
  readonly upgradeIntervalMs?: number;
}

type EnvelopeListener = (envelope: Envelope) => void;
type StateListener = (state: RealtimeState) => void;

const BACKOFF_MS = [1_000, 2_000, 5_000, 10_000, 30_000];

export class RealtimeClient {
  private readonly env: TransportEnv;
  private readonly connectTimeoutMs: number;
  private readonly upgradeIntervalMs: number;
  private readonly topics = new Set<string>();
  /** Topics waiting for a snapshot after a gap; their deltas are ignored until it arrives. */
  private readonly resyncing = new Set<string>();
  private readonly envelopeListeners = new Set<EnvelopeListener>();
  private readonly stateListeners = new Set<StateListener>();

  private session: SessionInfo | null = null;
  private transport: Transport | null = null;
  private attempt = 0;
  private failuresInRow = 0;
  private connectTimer: ReturnType<typeof setTimeout> | undefined;
  private livenessTimer: ReturnType<typeof setTimeout> | undefined;
  private upgradeTimer: ReturnType<typeof setTimeout> | undefined;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private running = false;
  private dropNext = false;
  private state: RealtimeState = {
    status: 'offline',
    transport: null,
    sessionId: null,
    lastSeq: 0,
    received: 0,
    gaps: 0,
    resyncs: 0,
    fallbacks: 0,
    lastError: null,
  };

  constructor(private readonly options: RealtimeClientOptions) {
    this.env = options.env ?? browserTransportEnv();
    this.connectTimeoutMs = options.connectTimeoutMs ?? 5_000;
    this.upgradeIntervalMs = options.upgradeIntervalMs ?? 60_000;
  }

  onEnvelope(listener: EnvelopeListener): () => void {
    this.envelopeListeners.add(listener);
    return () => this.envelopeListeners.delete(listener);
  }

  onState(listener: StateListener): () => void {
    this.stateListeners.add(listener);
    listener(this.state);
    return () => this.stateListeners.delete(listener);
  }

  getState(): RealtimeState {
    return this.state;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    this.attempt = 0;
    void this.connect();
  }

  stop(): void {
    this.running = false;
    this.clearTimers();
    this.transport?.close();
    this.transport = null;
    this.update({ status: 'offline', transport: null });
  }

  subscribe(topic: string): void {
    this.topics.add(topic);
    if (this.state.status === 'connected') this.sendSafe(clientMessage('subscribe', topic));
  }

  unsubscribe(topic: string): void {
    this.topics.delete(topic);
    if (this.state.status === 'connected') this.sendSafe(clientMessage('unsubscribe', topic));
  }

  /** Test aid for the spike page: silently drop the next envelope to provoke a gap + resync. */
  simulateGap(): void {
    this.dropNext = true;
  }

  // --- connection management -------------------------------------------------------------

  private order(): readonly TransportKind[] {
    return this.options.forcedTransport ? [this.options.forcedTransport] : TRANSPORT_ORDER;
  }

  private async connect(): Promise<void> {
    if (!this.running) return;
    this.update({ status: 'connecting' });
    try {
      this.session ??= await this.createSession();
    } catch (error) {
      this.scheduleRetry(`session: ${errorText(error)}`);
      return;
    }
    const kind = this.order()[this.attempt] ?? 'long-poll';
    const transport = this.makeTransport(kind, this.session);
    this.transport = transport;
    let contacted = false;
    this.connectTimer = setTimeout(() => {
      if (!contacted)
        this.transportFailed(
          transport,
          `${kind}: no contact within ${String(this.connectTimeoutMs)} ms`,
          false,
        );
    }, this.connectTimeoutMs);
    transport.open(this.state.lastSeq, {
      onAlive: () => {
        if (this.transport !== transport) return;
        if (!contacted) {
          contacted = true;
          clearTimeout(this.connectTimer);
          this.failuresInRow = 0;
          this.update({ status: 'connected', transport: kind, lastError: null });
          this.scheduleUpgrade(kind);
        }
        this.resetLiveness(transport);
      },
      onEnvelope: (envelope) => {
        if (this.transport === transport) this.handle(envelope);
      },
      onClose: (reason, fatal) => {
        if (this.transport === transport) this.transportFailed(transport, reason, fatal);
      },
    });
  }

  private async createSession(): Promise<SessionInfo> {
    const headers: Record<string, string> = {};
    const token = csrfToken(this.env.cookie());
    if (token) headers['X-XSRF-TOKEN'] = token;
    const response = await this.env.fetch(new URL('rt/session', this.options.base), {
      method: 'POST',
      headers,
      credentials: 'same-origin',
    });
    if (!response.ok) throw new Error(`HTTP ${String(response.status)}`);
    const info = sessionResponseSchema.parse(await response.json());
    // A fresh session starts at seq 0; everything we held must be rebuilt from snapshots.
    this.update({ sessionId: info.sessionId, lastSeq: 0 });
    return info;
  }

  private makeTransport(kind: TransportKind, session: SessionInfo): Transport {
    switch (kind) {
      case 'websocket':
        return new WebSocketTransport(this.env, this.options.base, session.sessionId);
      case 'sse':
        return new SseTransport(this.env, this.options.base, session.sessionId);
      case 'long-poll':
        return new LongPollTransport(
          this.env,
          this.options.base,
          session.sessionId,
          session.pollHoldMs,
        );
    }
  }

  private transportFailed(transport: Transport, reason: string, fatal: boolean): void {
    if (this.transport !== transport) return;
    this.clearTimers();
    transport.close();
    this.transport = null;
    this.update({ status: 'connecting', transport: null, lastError: reason });
    if (fatal) {
      // Session unknown/expired/foreign: start over with a new session and resubscribe.
      this.session = null;
      this.attempt = 0;
      void this.connect();
      return;
    }
    const order = this.order();
    if (this.attempt + 1 < order.length) {
      this.attempt++;
      this.update({ fallbacks: this.state.fallbacks + 1 });
      void this.connect();
    } else {
      this.attempt = 0;
      this.scheduleRetry(reason);
    }
  }

  private scheduleRetry(reason: string): void {
    const delay = BACKOFF_MS[Math.min(this.failuresInRow, BACKOFF_MS.length - 1)] ?? 30_000;
    this.failuresInRow++;
    this.update({ status: 'offline', transport: null, lastError: reason });
    this.retryTimer = setTimeout(() => void this.connect(), delay);
  }

  /** While on a worse transport, periodically try the best one again (§8.4). */
  private scheduleUpgrade(kind: TransportKind): void {
    clearTimeout(this.upgradeTimer);
    if (this.options.forcedTransport || kind === this.order()[0]) return;
    this.upgradeTimer = setTimeout(() => {
      if (!this.running || !this.transport) return;
      this.transport.close();
      this.transport = null;
      clearTimeout(this.livenessTimer);
      this.attempt = 0;
      void this.connect();
    }, this.upgradeIntervalMs);
  }

  private resetLiveness(transport: Transport): void {
    clearTimeout(this.livenessTimer);
    const heartbeat = this.session?.heartbeatMs ?? 10_000;
    const holdSlack = transport.kind === 'long-poll' ? (this.session?.pollHoldMs ?? 25_000) : 0;
    // §7: 3 x heartbeat without any sign of life = dead (also catches buffering SSE proxies).
    this.livenessTimer = setTimeout(
      () => {
        this.transportFailed(
          transport,
          `${transport.kind}: no data for ${String(3 * heartbeat)} ms`,
          false,
        );
      },
      3 * heartbeat + holdSlack,
    );
  }

  private clearTimers(): void {
    clearTimeout(this.connectTimer);
    clearTimeout(this.livenessTimer);
    clearTimeout(this.upgradeTimer);
    clearTimeout(this.retryTimer);
  }

  // --- envelope ordering (§6) -------------------------------------------------------------

  private handle(envelope: Envelope): void {
    if (envelope.type === 'heartbeat') return;
    if (this.dropNext) {
      this.dropNext = false;
      return;
    }
    const last = this.state.lastSeq;
    if (envelope.seq <= last) return; // duplicate after a re-attach
    this.update({ received: this.state.received + 1 });
    if (envelope.seq > last + 1) {
      this.update({ gaps: this.state.gaps + 1, lastSeq: envelope.seq });
      this.resyncAll();
      return;
    }
    this.update({ lastSeq: envelope.seq });

    const topic = typeof envelope.payload.topic === 'string' ? envelope.payload.topic : undefined;
    switch (envelope.type) {
      case 'hello':
        // A brand-new session (seq 1): subscribe everything we want.
        for (const t of this.topics) this.sendSafe(clientMessage('subscribe', t));
        break;
      case 'snapshot':
        if (topic) this.resyncing.delete(topic);
        break;
      case 'delta':
        if (topic && this.resyncing.has(topic)) return; // stale until the snapshot arrives
        break;
      case 'error':
        if (envelope.payload.code === 'resync-required') this.resyncAll();
        break;
      default:
        break;
    }
    for (const listener of this.envelopeListeners) {
      try {
        listener(envelope);
      } catch (error) {
        console.error('Realtime listener failed', error);
      }
    }
  }

  private resyncAll(): void {
    for (const topic of this.topics) {
      this.resyncing.add(topic);
      this.sendSafe(clientMessage('resync', topic));
    }
    this.update({ resyncs: this.state.resyncs + this.topics.size });
  }

  private sendSafe(message: ReturnType<typeof clientMessage>): void {
    this.transport?.send(message).catch((error: unknown) => {
      console.warn('Realtime send failed', error);
    });
  }

  private update(patch: Partial<RealtimeState>): void {
    this.state = { ...this.state, ...patch };
    for (const listener of this.stateListeners) {
      try {
        listener(this.state);
      } catch (error) {
        console.error('Realtime state listener failed', error);
      }
    }
  }
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
