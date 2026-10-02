import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { DiagPage } from './DiagPage';
import { resolveModes } from './modes';
import type { DiagnosticsReport } from './runDiagnostics';
import type { CapabilityReport, TransportReport } from './types';

const capabilities: CapabilityReport = {
  webgl1: { available: true, softwareRendered: false, renderer: 'Test GPU' },
  webgl2: { available: false, softwareRendered: false },
  secureContext: false,
  secureFeatures: [{ name: 'Clipboard API', available: false, fallback: 'Copy dialog instead.' }],
  browser: {
    userAgent: 'TestAgent/1.0',
    language: 'en',
    timeZone: 'UTC',
    screen: '1920x1080',
    devicePixelRatio: 1,
    maxTouchPoints: 0,
    hardwareConcurrency: 8,
  },
};
const transports: TransportReport = {
  ping: { ok: true, detail: 'Backend reachable.' },
  webSocket: { ok: false, detail: 'Blocked.' },
  sse: { ok: true, buffered: true, detail: 'Buffered.' },
  longPoll: { ok: true, detail: 'OK.' },
};
const report: DiagnosticsReport = {
  generatedAt: '2026-10-03T00:00:00Z',
  pageUrl: 'http://h/diag',
  capabilities,
  transports,
  modes: resolveModes(capabilities, transports),
};

describe('DiagPage', () => {
  it('shows the resulting modes and per-probe results', async () => {
    render(<DiagPage run={() => Promise.resolve(report)} />);

    expect(await screen.findByTestId('transport-mode')).toHaveTextContent('long-poll');
    expect(screen.getByTestId('render-mode')).toHaveTextContent('webgl');
    expect(screen.getByText('Blocked.')).toBeInTheDocument();
    expect(screen.getByText('degraded')).toBeInTheDocument();
    expect(screen.getByText('Copy dialog instead.')).toBeInTheDocument();
  });

  it('falls back to a selectable report when the clipboard is unavailable (plain HTTP)', async () => {
    render(<DiagPage run={() => Promise.resolve(report)} />);
    await screen.findByTestId('transport-mode');

    fireEvent.click(screen.getByRole('button', { name: 'Copy report' }));

    const textarea = await screen.findByLabelText('Diagnostics report JSON');
    expect((textarea as HTMLTextAreaElement).value).toContain('"transport": "long-poll"');
  });

  it('shows a readable message if the run itself fails', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    render(<DiagPage run={() => Promise.reject(new Error('kaputt'))} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('kaputt');
    consoleError.mockRestore();
  });
});
