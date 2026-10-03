import { useEffect, useRef, useState } from 'react';

import { applyDemoEnvelope, type DemoContact } from '../../realtime/demoPicture';
import { RealtimeClient, type RealtimeState } from '../../realtime/RealtimeClient';
import type { TransportKind } from '../../realtime/transports';

type Choice = 'auto' | TransportKind;

/**
 * Spike B page (ROADMAP P0 item 8): connects to the `demo` topic and shows which transport is in
 * use, sequence/gap/resync counters and the live contact count. "Force" and "Simulate gap" make the
 * fallback and resync paths testable by hand; the WS-blocking dev proxies (6445/6446) test the
 * automatic fallback.
 */
export function RealtimeSpikePage() {
  const [choice, setChoice] = useState<Choice>('auto');
  const [state, setState] = useState<RealtimeState>();
  const [contacts, setContacts] = useState(0);
  const [updatesPerSecond, setUpdatesPerSecond] = useState(0);
  const [log, setLog] = useState<string[]>([]);
  const clientRef = useRef<RealtimeClient | null>(null);

  useEffect(() => {
    const client = new RealtimeClient({
      base: new URL('.', window.location.href),
      forcedTransport: choice === 'auto' ? undefined : choice,
    });
    clientRef.current = client;
    const picture = new Map<number, DemoContact>();
    let updates = 0;
    const offState = client.onState((s) => {
      setState((previous) => {
        if (previous?.transport !== s.transport || previous.status !== s.status) {
          const line = `${new Date().toISOString().slice(11, 19)} ${s.status} ${s.transport ?? ''} ${s.lastError ?? ''}`;
          setLog((l) => [line, ...l].slice(0, 30));
        }
        return s;
      });
    });
    const offEnvelope = client.onEnvelope((e) => {
      applyDemoEnvelope(picture, e);
      if (e.type === 'delta' && Array.isArray(e.payload.upserts))
        updates += e.payload.upserts.length;
      if (e.type === 'snapshot' || e.type === 'error') {
        setLog((l) =>
          [`${new Date().toISOString().slice(11, 19)} ${e.type} seq ${String(e.seq)}`, ...l].slice(
            0,
            30,
          ),
        );
      }
    });
    const ticker = window.setInterval(() => {
      setContacts(picture.size);
      setUpdatesPerSecond(updates);
      updates = 0;
    }, 1000);
    client.subscribe('demo');
    client.start();
    return () => {
      window.clearInterval(ticker);
      offState();
      offEnvelope();
      client.stop();
    };
  }, [choice]);

  return (
    <main className="rt">
      <h1>Spike B - realtime transports</h1>
      <div className="rt-controls">
        <label>
          Transport
          <select
            value={choice}
            onChange={(e) => {
              setChoice(e.target.value as Choice);
            }}
          >
            <option value="auto">Automatic (WS → SSE → long-poll)</option>
            <option value="websocket">Force WebSocket</option>
            <option value="sse">Force SSE</option>
            <option value="long-poll">Force long-polling</option>
          </select>
        </label>
        <button
          type="button"
          onClick={() => {
            clientRef.current?.simulateGap();
          }}
        >
          Simulate gap
        </button>
      </div>
      <dl className="rt-grid">
        <dt>Status</dt>
        <dd data-testid="status">{state?.status ?? '-'}</dd>
        <dt>Transport</dt>
        <dd data-testid="transport">{state?.transport ?? '-'}</dd>
        <dt>Session</dt>
        <dd>
          <code>{state?.sessionId ?? '-'}</code>
        </dd>
        <dt>Last seq</dt>
        <dd data-testid="seq">{state?.lastSeq ?? 0}</dd>
        <dt>Envelopes</dt>
        <dd>{state?.received ?? 0}</dd>
        <dt>Gaps / resyncs</dt>
        <dd data-testid="gaps">
          {state?.gaps ?? 0} / {state?.resyncs ?? 0}
        </dd>
        <dt>Fallbacks</dt>
        <dd>{state?.fallbacks ?? 0}</dd>
        <dt>Demo contacts</dt>
        <dd data-testid="contacts">{contacts}</dd>
        <dt>Updates/s</dt>
        <dd data-testid="ups">{updatesPerSecond}</dd>
      </dl>
      <h2>Log</h2>
      <pre className="rt-log">{log.join('\n')}</pre>
    </main>
  );
}
