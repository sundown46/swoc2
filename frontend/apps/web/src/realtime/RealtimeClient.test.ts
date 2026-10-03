import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Envelope } from './protocol';
import { RealtimeClient } from './RealtimeClient';
import type { TransportEnv } from './transports';

/** Controllable WebSocket double. */
class FakeWebSocket {
  static OPEN = 1;
  static instances: FakeWebSocket[] = [];
  readyState = 1;
  sent: unknown[] = [];
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: ((e: { code: number }) => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
  }
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close() {
    this.readyState = 3;
  }
  deliver(envelope: Partial<Envelope> & { seq: number; type: string }) {
    this.onmessage?.({ data: JSON.stringify({ v: 1, payload: {}, ...envelope }) });
  }
}

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }
  close() {
    this.closed = true;
  }
  deliver(envelope: { seq: number; type: string; payload?: Record<string, unknown> }) {
    this.onmessage?.({ data: JSON.stringify({ v: 1, payload: {}, ...envelope }) });
  }
}

let sessions = 0;
const posts: { url: string; body: unknown }[] = [];

function env(): TransportEnv {
  const fetchImpl = vi.fn((input: URL | string, init?: RequestInit) => {
    const url = input.toString();
    if (url.endsWith('rt/session')) {
      sessions++;
      return Promise.resolve(
        new Response(
          JSON.stringify({
            sessionId: `s${String(sessions)}`,
            heartbeatMs: 1000,
            pollHoldMs: 2000,
          }),
          {
            status: 201,
          },
        ),
      );
    }
    if (url.includes('rt/send')) {
      posts.push({ url, body: JSON.parse(typeof init?.body === 'string' ? init.body : 'null') });
      return Promise.resolve(new Response(null, { status: 202 }));
    }
    if (url.includes('rt/poll')) {
      return new Promise<Response>(() => undefined); // held forever
    }
    return Promise.reject(new Error(`unexpected ${url}`));
  });
  return {
    WebSocket: FakeWebSocket as unknown as typeof WebSocket,
    EventSource: FakeEventSource as unknown as typeof EventSource,
    fetch: fetchImpl as unknown as typeof fetch,
    cookie: () => 'XSRF-TOKEN=abc',
  };
}

const base = new URL('http://h/swoc2/');

async function flush() {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}

function lastWs(): FakeWebSocket {
  const ws = FakeWebSocket.instances.at(-1);
  if (!ws) throw new Error('no websocket');
  return ws;
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeWebSocket.instances = [];
  FakeEventSource.instances = [];
  sessions = 0;
  posts.length = 0;
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('RealtimeClient', () => {
  it('connects via WebSocket, subscribes after hello and delivers snapshots', async () => {
    const client = new RealtimeClient({ base, env: env() });
    const received: Envelope[] = [];
    client.onEnvelope((e) => received.push(e));
    client.subscribe('demo');
    client.start();
    await flush();

    const ws = lastWs();
    expect(ws.url).toBe('ws://h/swoc2/rt/ws?session=s1&after=0');
    ws.deliver({ seq: 1, type: 'hello' });
    expect(ws.sent).toEqual([{ v: 1, type: 'subscribe', payload: { topic: 'demo' } }]);
    ws.deliver({ seq: 2, type: 'snapshot', payload: { topic: 'demo', items: [] } });

    expect(client.getState()).toMatchObject({
      status: 'connected',
      transport: 'websocket',
      lastSeq: 2,
    });
    expect(received.map((e) => e.type)).toEqual(['hello', 'snapshot']);
  });

  it('falls back to SSE when the WebSocket closes and resumes from the last seq', async () => {
    const client = new RealtimeClient({ base, env: env() });
    client.start();
    await flush();
    lastWs().deliver({ seq: 1, type: 'hello' });
    lastWs().onclose?.({ code: 1006 });
    await flush();

    const sse = FakeEventSource.instances.at(-1);
    expect(sse?.url).toBe('http://h/swoc2/rt/sse?session=s1&after=1');
    sse?.deliver({ seq: 2, type: 'delta', payload: { topic: 'demo' } });
    expect(client.getState()).toMatchObject({ transport: 'sse', lastSeq: 2, fallbacks: 1 });
  });

  it('falls back when the WebSocket gives no sign of life within the connect timeout', async () => {
    const client = new RealtimeClient({ base, env: env(), connectTimeoutMs: 500 });
    client.start();
    await flush();

    vi.advanceTimersByTime(500);
    await flush();

    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('detects a gap, resyncs and ignores deltas until the snapshot arrives', async () => {
    const client = new RealtimeClient({ base, env: env() });
    const received: Envelope[] = [];
    client.onEnvelope((e) => received.push(e));
    client.subscribe('demo');
    client.start();
    await flush();
    const ws = lastWs();
    ws.deliver({ seq: 1, type: 'hello' });
    ws.deliver({ seq: 2, type: 'snapshot', payload: { topic: 'demo' } });

    ws.deliver({ seq: 4, type: 'delta', payload: { topic: 'demo' } }); // 3 missing
    ws.deliver({ seq: 5, type: 'delta', payload: { topic: 'demo' } }); // stale, ignored
    ws.deliver({ seq: 6, type: 'snapshot', payload: { topic: 'demo' } });
    ws.deliver({ seq: 7, type: 'delta', payload: { topic: 'demo' } });

    expect(ws.sent.at(-1)).toEqual({ v: 1, type: 'resync', payload: { topic: 'demo' } });
    expect(received.map((e) => e.seq)).toEqual([1, 2, 6, 7]);
    expect(client.getState()).toMatchObject({ gaps: 1, resyncs: 1, lastSeq: 7 });
  });

  it('drops duplicates and invalid envelopes', async () => {
    const client = new RealtimeClient({ base, env: env() });
    const received: Envelope[] = [];
    client.onEnvelope((e) => received.push(e));
    client.start();
    await flush();
    const ws = lastWs();
    ws.deliver({ seq: 1, type: 'hello' });
    ws.deliver({ seq: 1, type: 'hello' });
    ws.onmessage?.({ data: 'garbage' });
    ws.onmessage?.({ data: JSON.stringify({ v: 2, seq: 2, type: 'delta', payload: {} }) });

    expect(received).toHaveLength(1);
    expect(client.getState().status).toBe('connected');
  });

  it('starts a new session and resubscribes when the session is unknown (4404)', async () => {
    const client = new RealtimeClient({ base, env: env() });
    client.subscribe('demo');
    client.start();
    await flush();
    lastWs().deliver({ seq: 1, type: 'hello' });
    lastWs().onclose?.({ code: 4404 });
    await flush();

    const ws = lastWs();
    expect(ws.url).toBe('ws://h/swoc2/rt/ws?session=s2&after=0');
    ws.deliver({ seq: 1, type: 'hello' });
    expect(ws.sent).toEqual([{ v: 1, type: 'subscribe', payload: { topic: 'demo' } }]);
  });

  it('treats a silent transport as dead after 3 heartbeats', async () => {
    const client = new RealtimeClient({ base, env: env() });
    client.start();
    await flush();
    lastWs().deliver({ seq: 1, type: 'hello' });

    vi.advanceTimersByTime(3 * 1000 + 1);
    await flush();

    expect(client.getState().transport).toBeNull();
    expect(FakeEventSource.instances).toHaveLength(1);
  });

  it('honours a forced transport and sends upstream with the CSRF header', async () => {
    const e = env();
    const client = new RealtimeClient({ base, env: e, forcedTransport: 'sse' });
    client.start();
    await flush();
    const sse = FakeEventSource.instances.at(-1);
    sse?.deliver({ seq: 1, type: 'hello' });
    client.subscribe('demo');
    await flush();

    expect(FakeWebSocket.instances).toHaveLength(0);
    expect(posts[0]?.body).toEqual({ v: 1, type: 'subscribe', payload: { topic: 'demo' } });
    const sendCall = vi
      .mocked(e.fetch)
      .mock.calls.find((c) => (c[0] as URL).toString().includes('rt/send'));
    expect((sendCall?.[1]?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc');
  });

  it('tries to upgrade back to WebSocket while degraded', async () => {
    const client = new RealtimeClient({ base, env: env(), upgradeIntervalMs: 10_000 });
    client.start();
    await flush();
    lastWs().onclose?.({ code: 1006 });
    await flush();
    FakeEventSource.instances.at(-1)?.deliver({ seq: 1, type: 'hello' });

    vi.advanceTimersByTime(10_000);
    await flush();

    expect(FakeWebSocket.instances).toHaveLength(2);
    expect(lastWs().url).toContain('after=1');
  });
});
