/**
 * Result types for the diagnostics page (GEN-010). Kept separate from the probes so the
 * mode-resolution logic and the UI can be tested without touching browser APIs.
 */

/** Outcome of one transport probe against the backend. */
export interface TransportProbeResult {
  readonly ok: boolean;
  /** Round-trip / time-to-first-data in milliseconds, when measured. */
  readonly latencyMs?: number;
  /** Human-readable detail: why it failed, or a notable observation (e.g. "buffered"). */
  readonly detail: string;
}

/** SSE additionally reports whether a proxy buffered the stream (ARCHITECTURE §6). */
export interface SseProbeResult extends TransportProbeResult {
  readonly buffered: boolean;
}

export interface WebGlInfo {
  readonly available: boolean;
  /** Unmasked renderer string when the browser exposes it, else the masked one. */
  readonly renderer?: string;
  /**
   * True when the context is only available with a major performance caveat, i.e. software
   * rendering (SwiftShader, llvmpipe). Such "WebGL" is slower than Canvas for our map.
   */
  readonly softwareRendered: boolean;
}

/** Feature that needs a secure context (GEN-012) and whether it is usable right now. */
export interface SecureFeature {
  readonly name: string;
  readonly available: boolean;
  /** What the app does instead when it is not available. */
  readonly fallback: string;
}

export interface BrowserInfo {
  readonly userAgent: string;
  readonly language: string;
  readonly timeZone: string;
  readonly screen: string;
  readonly devicePixelRatio: number;
  readonly maxTouchPoints: number;
  readonly hardwareConcurrency: number;
}

export interface CapabilityReport {
  readonly webgl1: WebGlInfo;
  readonly webgl2: WebGlInfo;
  readonly secureContext: boolean;
  readonly secureFeatures: readonly SecureFeature[];
  readonly browser: BrowserInfo;
}

export type TransportMode = 'websocket' | 'sse' | 'long-poll' | 'none';
export type RenderMode = 'webgl' | 'canvas';

export interface ResolvedModes {
  readonly transport: TransportMode;
  readonly render: RenderMode;
  /** Why these modes were chosen, one sentence each, for the operator reading the page. */
  readonly reasons: readonly string[];
}

export interface TransportReport {
  readonly ping: TransportProbeResult;
  readonly webSocket: TransportProbeResult;
  readonly sse: SseProbeResult;
  readonly longPoll: TransportProbeResult;
}
