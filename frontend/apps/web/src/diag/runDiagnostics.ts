import { resolveModes } from './modes';
import {
  browserProbeEnv,
  instanceBaseUrl,
  probeCapabilities,
  probeLongPoll,
  probePing,
  probeSse,
  probeWebSocket,
  type ProbeEnv,
} from './probes';
import type { CapabilityReport, ResolvedModes, TransportReport } from './types';

/** Everything the diagnostics page shows; also what "copy report" exports as JSON. */
export interface DiagnosticsReport {
  readonly generatedAt: string;
  readonly pageUrl: string;
  readonly capabilities: CapabilityReport;
  readonly transports: TransportReport;
  readonly modes: ResolvedModes;
}

/** Runs all probes. The transport probes run in parallel; each has its own timeout. */
export async function runDiagnostics(
  env: ProbeEnv = browserProbeEnv(),
  pageHref: string = window.location.href,
  capabilities: () => CapabilityReport = () => probeCapabilities(),
): Promise<DiagnosticsReport> {
  const base = instanceBaseUrl(pageHref);
  const caps = capabilities();
  const [ping, webSocket, sse, longPoll] = await Promise.all([
    probePing(env, base),
    probeWebSocket(env, base),
    probeSse(env, base),
    probeLongPoll(env, base),
  ]);
  const transports: TransportReport = { ping, webSocket, sse, longPoll };
  return {
    generatedAt: new Date().toISOString(),
    pageUrl: pageHref,
    capabilities: caps,
    transports,
    modes: resolveModes(caps, transports),
  };
}
