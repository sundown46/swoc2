import { useState } from 'react';

import { runDiagnostics, type DiagnosticsReport } from './runDiagnostics';
import type { TransportProbeResult } from './types';
import { copyText, useDiagnostics, type DiagnosticsState } from './useDiagnostics';

type BadgeState = 'ok' | 'warn' | 'fail';

function Badge({ state, children }: { state: BadgeState; children: string }) {
  return (
    <span className="diag-badge" data-state={state}>
      {children}
    </span>
  );
}

function TransportRow({
  name,
  result,
  warn,
}: {
  name: string;
  result: TransportProbeResult;
  warn?: boolean;
}) {
  const state: BadgeState = !result.ok ? 'fail' : warn ? 'warn' : 'ok';
  return (
    <tr>
      <th scope="row">{name}</th>
      <td>
        <Badge state={state}>{result.ok ? (warn ? 'degraded' : 'works') : 'fails'}</Badge>
      </td>
      <td>{result.detail}</td>
    </tr>
  );
}

function Report({ report }: { report: DiagnosticsReport }) {
  const { capabilities: caps, transports, modes } = report;
  return (
    <>
      <section className="diag-section" aria-labelledby="diag-modes">
        <h2 id="diag-modes">Resulting modes</h2>
        <div className="diag-modes">
          <div className="diag-mode">
            <div className="diag-mode-label">Realtime transport</div>
            <div className="diag-mode-value" data-testid="transport-mode">
              {modes.transport}
            </div>
          </div>
          <div className="diag-mode">
            <div className="diag-mode-label">Map renderer</div>
            <div className="diag-mode-value" data-testid="render-mode">
              {modes.render}
            </div>
          </div>
        </div>
        <ul>
          {modes.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      </section>

      <section className="diag-section" aria-labelledby="diag-transports">
        <h2 id="diag-transports">Network and transports</h2>
        <div className="diag-table-wrap">
          <table>
            <tbody>
              <TransportRow name="Backend (HTTP)" result={transports.ping} />
              <TransportRow name="WebSocket" result={transports.webSocket} />
              <TransportRow
                name="Server-Sent Events"
                result={transports.sse}
                warn={transports.sse.buffered}
              />
              <TransportRow name="Long-polling" result={transports.longPoll} />
            </tbody>
          </table>
        </div>
      </section>

      <section className="diag-section" aria-labelledby="diag-render">
        <h2 id="diag-render">Rendering</h2>
        <div className="diag-table-wrap">
          <table>
            <tbody>
              {(
                [
                  ['WebGL 2', caps.webgl2],
                  ['WebGL 1', caps.webgl1],
                ] as const
              ).map(([name, gl]) => (
                <tr key={name}>
                  <th scope="row">{name}</th>
                  <td>
                    <Badge state={!gl.available ? 'fail' : gl.softwareRendered ? 'warn' : 'ok'}>
                      {!gl.available ? 'missing' : gl.softwareRendered ? 'software' : 'available'}
                    </Badge>
                  </td>
                  <td>
                    {gl.renderer ? (
                      <code>{gl.renderer}</code>
                    ) : (
                      <span className="diag-muted">-</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="diag-section" aria-labelledby="diag-secure">
        <h2 id="diag-secure">Secure context</h2>
        <p>
          <Badge state={caps.secureContext ? 'ok' : 'warn'}>
            {caps.secureContext ? 'yes' : 'no'}
          </Badge>{' '}
          {caps.secureContext
            ? 'The page is served in a secure context (HTTPS or localhost).'
            : 'Plain HTTP: features below that need a secure context are limited (GEN-012).'}
        </p>
        <div className="diag-table-wrap">
          <table>
            <tbody>
              {caps.secureFeatures.map((feature) => (
                <tr key={feature.name}>
                  <th scope="row">{feature.name}</th>
                  <td>
                    <Badge state={feature.available ? 'ok' : 'warn'}>
                      {feature.available ? 'available' : 'limited'}
                    </Badge>
                  </td>
                  <td className={feature.available ? 'diag-muted' : undefined}>
                    {feature.available ? '-' : feature.fallback}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="diag-section" aria-labelledby="diag-browser">
        <h2 id="diag-browser">Browser</h2>
        <div className="diag-table-wrap">
          <table>
            <tbody>
              <tr>
                <th scope="row">User agent</th>
                <td>
                  <code>{caps.browser.userAgent}</code>
                </td>
              </tr>
              <tr>
                <th scope="row">Screen / pixel ratio</th>
                <td>
                  {caps.browser.screen} @ {caps.browser.devicePixelRatio}x
                </td>
              </tr>
              <tr>
                <th scope="row">Touch points</th>
                <td>{caps.browser.maxTouchPoints}</td>
              </tr>
              <tr>
                <th scope="row">CPU threads</th>
                <td>{caps.browser.hardwareConcurrency}</td>
              </tr>
              <tr>
                <th scope="row">Language / time zone</th>
                <td>
                  {caps.browser.language} / {caps.browser.timeZone}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

/**
 * The {@code /diag} page (GEN-010): reachable without login, shows what this browser and
 * network support and which transport/render mode SWOC2 would therefore use. Meant to be opened
 * on locked-down service computers, and its report pasted into a support request.
 */
export function DiagPage({ run = runDiagnostics }: { run?: () => Promise<DiagnosticsReport> }) {
  const { state, rerun } = useDiagnostics(run);
  const [fallbackText, setFallbackText] = useState<string>();
  const [copied, setCopied] = useState(false);

  const copyReport = (current: DiagnosticsState) => {
    if (current.status !== 'done') return;
    const json = JSON.stringify(current.report, null, 2);
    void copyText(json).then((ok) => {
      setCopied(ok);
      setFallbackText(ok ? undefined : json);
    });
  };

  return (
    <main className="diag">
      <h1>SWOC2 diagnostics</h1>
      <p className="diag-lead">
        Checks what this browser and network support. No login needed; nothing is stored.
      </p>
      <div className="diag-actions">
        <button
          type="button"
          onClick={() => {
            setFallbackText(undefined);
            setCopied(false);
            rerun();
          }}
          disabled={state.status === 'running'}
        >
          {state.status === 'running' ? 'Running checks...' : 'Run again'}
        </button>
        <button
          type="button"
          onClick={() => {
            copyReport(state);
          }}
          disabled={state.status !== 'done'}
        >
          {copied ? 'Report copied' : 'Copy report'}
        </button>
      </div>
      {fallbackText !== undefined && (
        <section className="diag-section" aria-labelledby="diag-copy">
          <h2 id="diag-copy">Report</h2>
          <p className="diag-muted">
            The clipboard is not available here (plain HTTP). Select the text and copy it manually.
          </p>
          <textarea
            readOnly
            value={fallbackText}
            onFocus={(e) => {
              e.currentTarget.select();
            }}
            aria-label="Diagnostics report JSON"
          />
        </section>
      )}
      {state.status === 'running' && (
        <p role="status">Running checks, this can take a few seconds...</p>
      )}
      {state.status === 'failed' && (
        <p role="alert">
          The diagnostics page itself failed: {state.message}. Please reload the page.
        </p>
      )}
      {state.status === 'done' && <Report report={state.report} />}
    </main>
  );
}
