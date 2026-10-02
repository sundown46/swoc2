import type {
  BrowserInfo,
  CapabilityReport,
  SecureFeature,
  SseProbeResult,
  TransportProbeResult,
  WebGlInfo,
} from './types';

/**
 * Browser and network probes for the diagnostics page (GEN-010). Every probe has a hard timeout
 * and turns any exception into a failed result: on a locked-down machine "this does not work" is
 * the expected answer, never a reason for the page itself to break (CLAUDE.md principle 1).
 *
 * Browser APIs are injected through {@link ProbeEnv} so the probes are unit-testable.
 */

export interface ProbeEnv {
  readonly WebSocket: typeof WebSocket;
  readonly EventSource: typeof EventSource;
  readonly fetch: typeof fetch;
  readonly now: () => number;
}

export const browserProbeEnv = (): ProbeEnv => ({
  WebSocket: window.WebSocket,
  EventSource: window.EventSource,
  fetch: window.fetch.bind(window),
  now: () => performance.now(),
});

/** Default per-probe timeout. Generous: slow corporate proxies are exactly what we diagnose. */
export const PROBE_TIMEOUT_MS = 8000;

/** Must match {@code DiagProbeController.SSE_EVENT_COUNT} / {@code SSE_EVENT_INTERVAL}. */
export const SSE_EVENT_COUNT = 3;
export const SSE_EVENT_INTERVAL_MS = 400;

/** Long-poll hold requested from the server (capped server-side at 3 s). */
export const POLL_HOLD_MS = 1500;

/**
 * Base URL of this SWOC2 instance, derived from the page URL. {@code /diag} lives directly
 * under the base path, so {@code {base}/diag} -> {@code {base}/} - this works behind a reverse
 * proxy on a sub-path without reading {@code config.json} first.
 */
export function instanceBaseUrl(pageHref: string): URL {
  return new URL('.', pageHref);
}

/** WebSocket URL for a path under the base, with ws/wss following the page's scheme. */
export function webSocketUrl(base: URL, path: string): string {
  const url = new URL(path, base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Plain HTTP round trip to {@code api/diag/ping}. */
export async function probePing(
  env: ProbeEnv,
  base: URL,
  timeoutMs = PROBE_TIMEOUT_MS,
): Promise<TransportProbeResult> {
  const start = env.now();
  try {
    const response = await env.fetch(new URL('api/diag/ping', base), {
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    });
    const latencyMs = Math.round(env.now() - start);
    if (!response.ok) {
      return { ok: false, latencyMs, detail: `HTTP ${String(response.status)}` };
    }
    return { ok: true, latencyMs, detail: 'Backend reachable.' };
  } catch (error) {
    return { ok: false, detail: `Request failed: ${errorText(error)}` };
  }
}

/** Long-poll probe: a held request must come back after about {@link POLL_HOLD_MS}. */
export async function probeLongPoll(
  env: ProbeEnv,
  base: URL,
  timeoutMs = PROBE_TIMEOUT_MS,
): Promise<TransportProbeResult> {
  const start = env.now();
  try {
    const url = new URL('api/diag/poll', base);
    url.searchParams.set('holdMs', String(POLL_HOLD_MS));
    const response = await env.fetch(url, {
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    });
    const latencyMs = Math.round(env.now() - start);
    if (!response.ok) {
      return { ok: false, latencyMs, detail: `HTTP ${String(response.status)}` };
    }
    return {
      ok: true,
      latencyMs,
      detail: `Held request returned after ${String(latencyMs)} ms (server held ${String(POLL_HOLD_MS)} ms).`,
    };
  } catch (error) {
    return { ok: false, detail: `Held request failed: ${errorText(error)}` };
  }
}

/** WebSocket probe: open {@code api/diag/ws}, send one message, expect the echo. */
export function probeWebSocket(
  env: ProbeEnv,
  base: URL,
  timeoutMs = PROBE_TIMEOUT_MS,
): Promise<TransportProbeResult> {
  return new Promise((resolve) => {
    const start = env.now();
    let socket: WebSocket | undefined;
    let settled = false;
    const finish = (result: TransportProbeResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        socket?.close();
      } catch {
        // Closing a socket that never opened can throw in some browsers; irrelevant here.
      }
      resolve(result);
    };
    const timer = setTimeout(() => {
      finish({
        ok: false,
        detail: `No echo within ${String(timeoutMs)} ms (upgrade blocked or stalled).`,
      });
    }, timeoutMs);
    try {
      const token = `diag-${String(Math.random()).slice(2, 10)}`;
      socket = new env.WebSocket(webSocketUrl(base, 'api/diag/ws'));
      socket.onopen = () => {
        socket?.send(token);
      };
      socket.onmessage = (event: MessageEvent) => {
        if (event.data === token) {
          const latencyMs = Math.round(env.now() - start);
          finish({
            ok: true,
            latencyMs,
            detail: `Upgrade and echo OK (connect + round trip ${String(latencyMs)} ms).`,
          });
        }
      };
      socket.onerror = () => {
        finish({ ok: false, detail: 'Connection failed (WebSocket upgrade refused or blocked).' });
      };
      socket.onclose = (event: CloseEvent) => {
        finish({ ok: false, detail: `Closed before echo (code ${String(event.code)}).` });
      };
    } catch (error) {
      finish({ ok: false, detail: `Could not open WebSocket: ${errorText(error)}` });
    }
  });
}

/**
 * SSE probe: the server sends {@link SSE_EVENT_COUNT} events {@link SSE_EVENT_INTERVAL_MS}
 * apart. If they all arrive within a fraction of that spacing, a proxy buffered the stream -
 * which breaks SSE as a live transport even though it technically "works".
 */
export function probeSse(
  env: ProbeEnv,
  base: URL,
  timeoutMs = PROBE_TIMEOUT_MS,
): Promise<SseProbeResult> {
  return new Promise((resolve) => {
    const start = env.now();
    const arrivals: number[] = [];
    let source: EventSource | undefined;
    let settled = false;
    const finish = (result: SseProbeResult) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      source?.close();
      resolve(result);
    };
    const timer = setTimeout(() => {
      finish({
        ok: false,
        buffered: false,
        detail: `Received ${String(arrivals.length)} of ${String(SSE_EVENT_COUNT)} events within ${String(timeoutMs)} ms.`,
      });
    }, timeoutMs);
    try {
      source = new env.EventSource(new URL('api/diag/sse', base).toString());
      source.addEventListener('probe', () => {
        arrivals.push(env.now());
        if (arrivals.length < SSE_EVENT_COUNT) return;
        const first = arrivals[0] ?? start;
        const last = arrivals[arrivals.length - 1] ?? first;
        const spreadMs = last - first;
        const expectedSpreadMs = SSE_EVENT_INTERVAL_MS * (SSE_EVENT_COUNT - 1);
        const buffered = spreadMs < expectedSpreadMs / 4;
        finish({
          ok: true,
          buffered,
          latencyMs: Math.round(first - start),
          detail: buffered
            ? `All events arrived together (spread ${String(Math.round(spreadMs))} ms, expected ~${String(expectedSpreadMs)} ms): a proxy buffers SSE.`
            : `Events streamed as sent (spread ${String(Math.round(spreadMs))} ms).`,
        });
      });
      source.onerror = () => {
        // EventSource also fires "error" when the server completes the stream normally; only
        // count it as a failure if not all events have arrived yet.
        if (arrivals.length < SSE_EVENT_COUNT) {
          finish({
            ok: false,
            buffered: false,
            detail: `Stream failed after ${String(arrivals.length)} of ${String(SSE_EVENT_COUNT)} events.`,
          });
        }
      };
    } catch (error) {
      finish({
        ok: false,
        buffered: false,
        detail: `Could not open EventSource: ${errorText(error)}`,
      });
    }
  });
}

/** WebGL availability for one context type, including software-rendering detection. */
export function probeWebGl(
  kind: 'webgl' | 'webgl2',
  createCanvas: () => HTMLCanvasElement = () => document.createElement('canvas'),
): WebGlInfo {
  try {
    const canvas = createCanvas();
    const gl = canvas.getContext(kind) as WebGLRenderingContext | WebGL2RenderingContext | null;
    if (!gl) {
      return { available: false, softwareRendered: false };
    }
    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
    const renderer = String(
      debugInfo ? gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER),
    );
    // A fresh canvas, because a canvas only ever hands out one context type.
    const strict = createCanvas().getContext(kind, { failIfMajorPerformanceCaveat: true });
    const softwareRendered = strict === null || /swiftshader|llvmpipe|software/i.test(renderer);
    return { available: true, renderer, softwareRendered };
  } catch {
    return { available: false, softwareRendered: false };
  }
}

/** Features gated on a secure context (GEN-012) and what the app falls back to without them. */
export function probeSecureFeatures(win: Window = window): SecureFeature[] {
  const nav = win.navigator as Partial<Navigator>;
  return [
    {
      name: 'Clipboard API',
      available: win.isSecureContext && typeof nav.clipboard?.writeText === 'function',
      fallback: 'Copy actions open a dialog with selectable text instead.',
    },
    {
      name: 'Desktop notifications',
      available: win.isSecureContext && 'Notification' in win,
      fallback: 'Alarms are shown inside the app only.',
    },
    {
      name: 'Service worker',
      available: win.isSecureContext && 'serviceWorker' in nav,
      fallback: 'No offline caching of the app shell.',
    },
    {
      name: 'Web Crypto (subtle)',
      available: win.isSecureContext && typeof win.crypto.subtle === 'object',
      fallback: 'Client-side hashing/crypto features are disabled.',
    },
  ];
}

export function probeBrowser(win: Window = window): BrowserInfo {
  return {
    userAgent: win.navigator.userAgent,
    language: win.navigator.language,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    screen: `${String(win.screen.width)}x${String(win.screen.height)}`,
    devicePixelRatio: win.devicePixelRatio,
    maxTouchPoints: win.navigator.maxTouchPoints,
    hardwareConcurrency: win.navigator.hardwareConcurrency,
  };
}

/** All synchronous capability probes. */
export function probeCapabilities(win: Window = window): CapabilityReport {
  return {
    webgl1: probeWebGl('webgl'),
    webgl2: probeWebGl('webgl2'),
    secureContext: win.isSecureContext,
    secureFeatures: probeSecureFeatures(win),
    browser: probeBrowser(win),
  };
}
