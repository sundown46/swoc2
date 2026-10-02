import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  instanceBaseUrl,
  probeLongPoll,
  probePing,
  probeSse,
  probeWebGl,
  probeWebSocket,
  SSE_EVENT_COUNT,
  SSE_EVENT_INTERVAL_MS,
  webSocketUrl,
  type ProbeEnv,
} from './probes';

describe('URL helpers', () => {
  it('derives the instance base from the /diag page URL, including sub-paths', () => {
    expect(instanceBaseUrl('http://host:5080/diag').toString()).toBe('http://host:5080/');
    expect(instanceBaseUrl('https://example.org/swoc2/diag?x=1').toString()).toBe(
      'https://example.org/swoc2/',
    );
  });

  it('maps http/https to ws/wss', () => {
    expect(webSocketUrl(new URL('http://h/swoc2/'), 'api/diag/ws')).toBe(
      'ws://h/swoc2/api/diag/ws',
    );
    expect(webSocketUrl(new URL('https://h/'), 'api/diag/ws')).toBe('wss://h/api/diag/ws');
  });
});

/** Minimal controllable WebSocket double. */
class FakeWebSocket {
  static last: FakeWebSocket | undefined;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  sent: string[] = [];
  constructor(readonly url: string) {
    FakeWebSocket.last = this;
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    /* no-op */
  }
}

/** Minimal controllable EventSource double. */
class FakeEventSource {
  static last: FakeEventSource | undefined;
  listeners: (() => void)[] = [];
  onerror: (() => void) | null = null;
  closed = false;
  constructor(readonly url: string) {
    FakeEventSource.last = this;
  }
  addEventListener(_name: string, listener: () => void) {
    this.listeners.push(listener);
  }
  emit() {
    this.listeners.forEach((l) => {
      l();
    });
  }
  close() {
    this.closed = true;
  }
}

let clock = 0;
const env = (fetchImpl: typeof fetch = vi.fn()): ProbeEnv => ({
  WebSocket: FakeWebSocket as unknown as typeof WebSocket,
  EventSource: FakeEventSource as unknown as typeof EventSource,
  fetch: fetchImpl,
  now: () => clock,
});
const base = new URL('http://h/swoc2/');

beforeEach(() => {
  clock = 0;
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe('probeWebSocket', () => {
  it('succeeds when the echo comes back', async () => {
    const result = probeWebSocket(env(), base);
    const ws = FakeWebSocket.last;
    expect(ws?.url).toBe('ws://h/swoc2/api/diag/ws');
    ws?.onopen?.();
    clock = 42;
    ws?.onmessage?.({ data: ws.sent[0] });
    await expect(result).resolves.toMatchObject({ ok: true, latencyMs: 42 });
  });

  it('fails on error', async () => {
    const result = probeWebSocket(env(), base);
    FakeWebSocket.last?.onerror?.();
    await expect(result).resolves.toMatchObject({ ok: false });
  });

  it('times out when nothing happens', async () => {
    const result = probeWebSocket(env(), base, 1000);
    vi.advanceTimersByTime(1000);
    await expect(result).resolves.toMatchObject({
      ok: false,
      detail: expect.stringContaining('No echo') as unknown,
    });
  });
});

describe('probeSse', () => {
  it('detects a streaming connection', async () => {
    const result = probeSse(env(), base);
    const es = FakeEventSource.last;
    for (let i = 0; i < SSE_EVENT_COUNT; i++) {
      clock = 100 + i * SSE_EVENT_INTERVAL_MS;
      es?.emit();
    }
    await expect(result).resolves.toMatchObject({ ok: true, buffered: false, latencyMs: 100 });
    expect(es?.closed).toBe(true);
  });

  it('detects a buffering proxy', async () => {
    const result = probeSse(env(), base);
    clock = 1300;
    for (let i = 0; i < SSE_EVENT_COUNT; i++) FakeEventSource.last?.emit();
    await expect(result).resolves.toMatchObject({ ok: true, buffered: true });
  });

  it('fails when the stream errors early', async () => {
    const result = probeSse(env(), base);
    FakeEventSource.last?.emit();
    FakeEventSource.last?.onerror?.();
    await expect(result).resolves.toMatchObject({ ok: false });
  });
});

describe('HTTP probes', () => {
  it('ping reports HTTP errors as failures', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('', { status: 502 }));
    await expect(probePing(env(fetchImpl), base)).resolves.toMatchObject({
      ok: false,
      detail: 'HTTP 502',
    });
    expect(String(fetchImpl.mock.calls[0]?.[0])).toBe('http://h/swoc2/api/diag/ping');
  });

  it('ping turns network errors into failures, never throws', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(probePing(env(fetchImpl), base)).resolves.toMatchObject({ ok: false });
  });

  it('long-poll requests a hold and succeeds on 200', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    await expect(probeLongPoll(env(fetchImpl), base)).resolves.toMatchObject({ ok: true });
    expect(String(fetchImpl.mock.calls[0]?.[0])).toBe('http://h/swoc2/api/diag/poll?holdMs=1500');
  });
});

describe('probeWebGl', () => {
  const canvasWith = (context: unknown) => () =>
    ({ getContext: () => context }) as unknown as HTMLCanvasElement;

  it('reports a missing context', () => {
    expect(probeWebGl('webgl2', canvasWith(null))).toEqual({
      available: false,
      softwareRendered: false,
    });
  });

  it('flags software renderers', () => {
    const gl = { getExtension: () => null, getParameter: () => 'Google SwiftShader', RENDERER: 1 };
    expect(probeWebGl('webgl', canvasWith(gl))).toMatchObject({
      available: true,
      softwareRendered: true,
    });
  });

  it('reports hardware renderers', () => {
    const gl = { getExtension: () => null, getParameter: () => 'NVIDIA GeForce', RENDERER: 1 };
    expect(probeWebGl('webgl', canvasWith(gl))).toMatchObject({
      available: true,
      softwareRendered: false,
    });
  });

  it('never throws', () => {
    const throwing = () => {
      throw new Error('boom');
    };
    expect(probeWebGl('webgl', throwing).available).toBe(false);
  });
});
