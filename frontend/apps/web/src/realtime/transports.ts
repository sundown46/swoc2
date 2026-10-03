import { CloseCode, parseEnvelope, type ClientMessage, type Envelope } from './protocol';

/**
 * The three interchangeable transports (docs/realtime-protocol.md §1). A transport only moves
 * envelopes; ordering, gap detection and fallback live in {@link RealtimeClient}.
 */

export type TransportKind = 'websocket' | 'sse' | 'long-poll';

export interface TransportHandlers {
  /** Called for every valid envelope, in arrival order. */
  onEnvelope(envelope: Envelope): void;
  /** Any sign of life (message, or an answered long-poll even if empty); feeds the liveness check. */
  onAlive(): void;
  /** The transport is gone. `fatal` = the session itself is unusable (unknown/foreign). */
  onClose(reason: string, fatal: boolean): void;
}

export interface Transport {
  readonly kind: TransportKind;
  /** Attaches to the session and starts receiving envelopes after `after`. */
  open(after: number, handlers: TransportHandlers): void;
  send(message: ClientMessage): Promise<void>;
  close(): void;
}

/** Browser APIs, injectable for tests. */
export interface TransportEnv {
  readonly WebSocket: typeof WebSocket;
  readonly EventSource: typeof EventSource;
  readonly fetch: typeof fetch;
  readonly cookie: () => string;
}

export const browserTransportEnv = (): TransportEnv => ({
  WebSocket: window.WebSocket,
  EventSource: window.EventSource,
  fetch: window.fetch.bind(window),
  cookie: () => document.cookie,
});

/** Reads the CSRF token cookie set by the backend (ARCHITECTURE §7). */
export function csrfToken(cookie: string): string | undefined {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(cookie);
  return match?.[1] === undefined ? undefined : decodeURIComponent(match[1]);
}

function wsUrl(base: URL, path: string): string {
  const url = new URL(path, base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

/** Upstream for SSE and long-poll: `POST rt/send` with the CSRF header. */
async function postSend(
  env: TransportEnv,
  base: URL,
  sessionId: string,
  message: ClientMessage,
): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = csrfToken(env.cookie());
  if (token) headers['X-XSRF-TOKEN'] = token;
  const response = await env.fetch(
    new URL(`rt/send?session=${encodeURIComponent(sessionId)}`, base),
    {
      method: 'POST',
      headers,
      body: JSON.stringify(message),
      credentials: 'same-origin',
    },
  );
  if (!response.ok) throw new Error(`rt/send failed: HTTP ${String(response.status)}`);
}

export class WebSocketTransport implements Transport {
  readonly kind = 'websocket';
  private socket: WebSocket | undefined;
  private closedByUs = false;

  constructor(
    private readonly env: TransportEnv,
    private readonly base: URL,
    private readonly sessionId: string,
  ) {}

  open(after: number, handlers: TransportHandlers): void {
    const url = wsUrl(
      this.base,
      `rt/ws?session=${encodeURIComponent(this.sessionId)}&after=${String(after)}`,
    );
    const socket = new this.env.WebSocket(url);
    this.socket = socket;
    socket.onmessage = (event: MessageEvent) => {
      handlers.onAlive();
      const envelope = parseEnvelope(event.data);
      if (envelope) handlers.onEnvelope(envelope);
    };
    socket.onclose = (event: CloseEvent) => {
      if (this.closedByUs) return;
      const fatal = event.code === CloseCode.unknownSession || event.code === CloseCode.forbidden;
      handlers.onClose(`websocket closed (${String(event.code)})`, fatal);
    };
    socket.onerror = () => {
      // onclose follows with the details.
    };
  }

  send(message: ClientMessage): Promise<void> {
    if (this.socket?.readyState !== this.env.WebSocket.OPEN) {
      return Promise.reject(new Error('websocket not open'));
    }
    this.socket.send(JSON.stringify(message));
    return Promise.resolve();
  }

  close(): void {
    this.closedByUs = true;
    try {
      this.socket?.close();
    } catch {
      // never opened
    }
  }
}

export class SseTransport implements Transport {
  readonly kind = 'sse';
  private source: EventSource | undefined;

  constructor(
    private readonly env: TransportEnv,
    private readonly base: URL,
    private readonly sessionId: string,
  ) {}

  open(after: number, handlers: TransportHandlers): void {
    const url = new URL(
      `rt/sse?session=${encodeURIComponent(this.sessionId)}&after=${String(after)}`,
      this.base,
    );
    const source = new this.env.EventSource(url.toString(), { withCredentials: true });
    this.source = source;
    source.onmessage = (event: MessageEvent) => {
      handlers.onAlive();
      const envelope = parseEnvelope(event.data);
      if (envelope) handlers.onEnvelope(envelope);
    };
    source.onerror = () => {
      // EventSource would reconnect by itself (with Last-Event-ID), but the client decides about
      // fallback, so treat any error as "transport gone" and let it re-attach with its own seq.
      this.close();
      handlers.onClose('sse error', false);
    };
  }

  send(message: ClientMessage): Promise<void> {
    return postSend(this.env, this.base, this.sessionId, message);
  }

  close(): void {
    this.source?.close();
  }
}

export class LongPollTransport implements Transport {
  readonly kind = 'long-poll';
  private abort: AbortController | undefined;
  private stopped = false;

  constructor(
    private readonly env: TransportEnv,
    private readonly base: URL,
    private readonly sessionId: string,
    private readonly holdMs: number,
  ) {}

  open(after: number, handlers: TransportHandlers): void {
    this.stopped = false;
    let lastSeq = after;
    const loop = async () => {
      while (!this.stopped) {
        this.abort = new AbortController();
        // Generous client timeout: hold + network slack; a stuck proxy must not hang us forever.
        const timer = setTimeout(() => this.abort?.abort(), this.holdMs + 15_000);
        try {
          const response = await this.env.fetch(
            new URL(
              `rt/poll?session=${encodeURIComponent(this.sessionId)}&after=${String(lastSeq)}`,
              this.base,
            ),
            { credentials: 'same-origin', cache: 'no-store', signal: this.abort.signal },
          );
          if (response.status === 404 || response.status === 403) {
            handlers.onClose(`long-poll HTTP ${String(response.status)}`, true);
            return;
          }
          if (!response.ok) throw new Error(`HTTP ${String(response.status)}`);
          const body: unknown = await response.json();
          if (!Array.isArray(body)) throw new Error('poll response is not an array');
          handlers.onAlive();
          for (const raw of body) {
            const envelope = parseEnvelope(raw);
            if (!envelope) continue;
            if (envelope.type !== 'heartbeat') lastSeq = Math.max(lastSeq, envelope.seq);
            handlers.onEnvelope(envelope);
            if (this.isStopped()) return;
          }
        } catch (error) {
          if (!this.isStopped())
            handlers.onClose(
              `long-poll failed: ${error instanceof Error ? error.message : String(error)}`,
              false,
            );
          return;
        } finally {
          clearTimeout(timer);
        }
      }
    };
    void loop();
  }

  /** Method, not a field read, so TypeScript doesn't narrow it inside the async loop. */
  private isStopped(): boolean {
    return this.stopped;
  }

  send(message: ClientMessage): Promise<void> {
    return postSend(this.env, this.base, this.sessionId, message);
  }

  close(): void {
    this.stopped = true;
    this.abort?.abort();
  }
}
