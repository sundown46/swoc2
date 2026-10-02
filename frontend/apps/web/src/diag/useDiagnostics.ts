import { useCallback, useEffect, useState } from 'react';

import { runDiagnostics, type DiagnosticsReport } from './runDiagnostics';

export type DiagnosticsState =
  | { readonly status: 'running' }
  | { readonly status: 'done'; readonly report: DiagnosticsReport }
  | { readonly status: 'failed'; readonly message: string };

/**
 * Runs the diagnostics once on mount and again on every {@code rerun()}. A run superseded by a
 * newer one (or by unmounting) never overwrites the newer result.
 */
export function useDiagnostics(run: () => Promise<DiagnosticsReport> = runDiagnostics) {
  const [state, setState] = useState<DiagnosticsState>({ status: 'running' });
  const [runId, setRunId] = useState(0);

  useEffect(() => {
    let superseded = false;
    run().then(
      (report) => {
        if (!superseded) setState({ status: 'done', report });
      },
      (error: unknown) => {
        // Every probe already catches its own failures; reaching this is a bug in the page.
        console.error('Diagnostics run failed', error);
        if (!superseded) {
          setState({
            status: 'failed',
            message: error instanceof Error ? error.message : String(error),
          });
        }
      },
    );
    return () => {
      superseded = true;
    };
  }, [run, runId]);

  const rerun = useCallback(() => {
    setState({ status: 'running' });
    setRunId((id) => id + 1);
  }, []);

  return { state, rerun };
}

/**
 * Copies text via the Clipboard API when it is usable (secure context only, GEN-012).
 * Returns false when it is not, so the caller can show a selectable-text fallback instead.
 */
export async function copyText(
  text: string,
  nav: Partial<Navigator> = navigator,
): Promise<boolean> {
  if (!window.isSecureContext || typeof nav.clipboard?.writeText !== 'function') return false;
  try {
    await nav.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}
