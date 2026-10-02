import type { CapabilityReport, ResolvedModes, TransportMode, TransportReport } from './types';

/**
 * Picks the transport and render mode the app would use with these probe results, in the
 * preference order of ARCHITECTURE §6 (WebSocket, then SSE + POST, then long-polling) and §9
 * (WebGL unless missing or software-rendered, else Canvas). Pure, so it is unit-tested; the real
 * client-side negotiation (Spike B) must stay consistent with it.
 */
export function resolveModes(
  capabilities: CapabilityReport,
  transports: TransportReport,
): ResolvedModes {
  const reasons: string[] = [];

  let transport: TransportMode;
  if (transports.webSocket.ok) {
    transport = 'websocket';
    reasons.push('WebSocket upgrade works end to end, so the primary transport is used.');
  } else if (transports.sse.ok && !transports.sse.buffered) {
    transport = 'sse';
    reasons.push('WebSocket is blocked; SSE streams unbuffered, so SSE + HTTPS POST is used.');
  } else if (transports.longPoll.ok) {
    transport = 'long-poll';
    reasons.push(
      transports.sse.ok
        ? 'WebSocket is blocked and SSE is buffered by a proxy, so long-polling is used.'
        : 'WebSocket and SSE are blocked, so HTTPS long-polling is used.',
    );
  } else {
    transport = 'none';
    reasons.push(
      'No realtime transport works from this browser/network: the live picture cannot update.',
    );
  }

  const gl = capabilities.webgl2.available ? capabilities.webgl2 : capabilities.webgl1;
  let render: ResolvedModes['render'];
  if (!gl.available) {
    render = 'canvas';
    reasons.push(
      'WebGL is not available, so the Canvas renderer with earlier aggregation is used.',
    );
  } else if (gl.softwareRendered) {
    render = 'canvas';
    reasons.push(
      'WebGL is only available via software rendering (slower than Canvas), so Canvas is used.',
    );
  } else {
    render = 'webgl';
    reasons.push(
      `${capabilities.webgl2.available ? 'WebGL 2' : 'WebGL 1'} is hardware-accelerated, so the WebGL renderer is used.`,
    );
  }

  return { transport, render, reasons };
}
