import { describe, expect, it } from 'vitest';

import { resolveModes } from './modes';
import type { CapabilityReport, TransportReport } from './types';

const ok = { ok: true, detail: '' };
const fail = { ok: false, detail: '' };

const caps = (overrides: Partial<CapabilityReport> = {}): CapabilityReport => ({
  webgl1: { available: true, softwareRendered: false },
  webgl2: { available: true, softwareRendered: false },
  secureContext: false,
  secureFeatures: [],
  browser: {
    userAgent: '',
    language: 'en',
    timeZone: 'UTC',
    screen: '1x1',
    devicePixelRatio: 1,
    maxTouchPoints: 0,
    hardwareConcurrency: 1,
  },
  ...overrides,
});

const transports = (overrides: Partial<TransportReport> = {}): TransportReport => ({
  ping: ok,
  webSocket: ok,
  sse: { ...ok, buffered: false },
  longPoll: ok,
  ...overrides,
});

describe('resolveModes', () => {
  it('prefers WebSocket and hardware WebGL', () => {
    const modes = resolveModes(caps(), transports());
    expect(modes.transport).toBe('websocket');
    expect(modes.render).toBe('webgl');
  });

  it('falls back to SSE when WebSocket is blocked', () => {
    expect(resolveModes(caps(), transports({ webSocket: fail })).transport).toBe('sse');
  });

  it('skips buffered SSE in favour of long-polling', () => {
    const modes = resolveModes(
      caps(),
      transports({ webSocket: fail, sse: { ...ok, buffered: true } }),
    );
    expect(modes.transport).toBe('long-poll');
    expect(modes.reasons.join(' ')).toContain('buffered');
  });

  it('reports no transport when everything fails', () => {
    const modes = resolveModes(
      caps(),
      transports({ webSocket: fail, sse: { ...fail, buffered: false }, longPoll: fail }),
    );
    expect(modes.transport).toBe('none');
  });

  it('uses WebGL 1 when WebGL 2 is missing', () => {
    const modes = resolveModes(
      caps({ webgl2: { available: false, softwareRendered: false } }),
      transports(),
    );
    expect(modes.render).toBe('webgl');
    expect(modes.reasons.join(' ')).toContain('WebGL 1');
  });

  it('uses Canvas without WebGL or with software-only WebGL', () => {
    const none = { available: false, softwareRendered: false };
    expect(resolveModes(caps({ webgl1: none, webgl2: none }), transports()).render).toBe('canvas');
    const software = { available: true, softwareRendered: true, renderer: 'SwiftShader' };
    expect(resolveModes(caps({ webgl1: software, webgl2: software }), transports()).render).toBe(
      'canvas',
    );
  });
});
