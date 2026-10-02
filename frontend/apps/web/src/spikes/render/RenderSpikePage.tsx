import { useEffect, useRef, useState } from 'react';

import {
  RenderBenchmark,
  resultRow,
  type BenchmarkResult,
  type BenchmarkSettings,
  type RendererKind,
} from './benchmark';

const COUNTS = [10_000, 50_000, 100_000];
const UPDATE_RATES = [0, 2_000, 10_000];

/**
 * Spike A page (ROADMAP P0 item 7, NFR-001): pick renderer, contact count and update rate,
 * then either pan around by hand (live fps shown) or run the scripted 20 s benchmark. Results
 * are listed as Markdown rows for the spike ADR.
 */
export function RenderSpikePage() {
  const mapRef = useRef<HTMLDivElement>(null);
  const benchRef = useRef<RenderBenchmark | null>(null);
  const [settings, setSettings] = useState<BenchmarkSettings>({
    count: 100_000,
    renderer: 'webgl',
    updatesPerSecond: 10_000,
    durationSeconds: 20,
    gpuHitDetection: false,
  });
  const [status, setStatus] = useState('Idle. Choose settings and press "Load".');
  const [liveFps, setLiveFps] = useState<number>();
  const [running, setRunning] = useState(false);
  const [results, setResults] = useState<BenchmarkResult[]>([]);
  const [setupInfo, setSetupInfo] = useState<{ setupMs: number; atlasSymbols: number }>();

  useEffect(() => {
    if (!mapRef.current) return;
    const bench = new RenderBenchmark(mapRef.current);
    benchRef.current = bench;
    return () => {
      bench.teardown();
    };
  }, []);

  const load = () => {
    const bench = benchRef.current;
    if (!bench) return;
    setStatus('Building picture...');
    // Let React paint the status before the (blocking) setup.
    window.setTimeout(() => {
      try {
        const info = bench.setup(settings);
        setSetupInfo(info);
        bench.startLiveFps(setLiveFps);
        setStatus(
          `Loaded ${settings.count.toLocaleString('en')} contacts (${settings.renderer}) in ${String(info.setupMs)} ms. Pan/zoom by hand or run the benchmark.`,
        );
      } catch (error) {
        console.error('Render spike setup failed', error);
        setStatus(`Setup failed: ${error instanceof Error ? error.message : String(error)}`);
      }
    }, 20);
  };

  const run = () => {
    const bench = benchRef.current;
    if (!bench || !setupInfo) return;
    setRunning(true);
    bench.stopLiveFps();
    setStatus(
      `Running scripted benchmark for ${String(settings.durationSeconds)} s - don't touch the map...`,
    );
    bench
      .run(settings, setupInfo)
      .then((result) => {
        setResults((r) => [...r, result]);
        setStatus(
          `Done: ${String(result.stats.avgFps)} fps average, ${String(result.stats.onePercentLowFps)} fps 1 % low.`,
        );
      })
      .catch((error: unknown) => {
        console.error('Render benchmark failed', error);
        setStatus('Benchmark failed, see console.');
      })
      .finally(() => {
        setRunning(false);
        bench.startLiveFps(setLiveFps);
      });
  };

  const table = [
    '| Renderer | Contacts | Updates | avg fps | 1 % low fps | p95 frame ms | frames > 50 ms | setup | viewport |',
    '|---|---|---|---|---|---|---|---|---|',
    ...results.map(resultRow),
  ].join('\n');

  return (
    <div className="spike">
      <div className="spike-panel">
        <h1>Spike A - contact rendering</h1>
        <label>
          Renderer
          <select
            value={settings.renderer}
            onChange={(e) => {
              setSettings({ ...settings, renderer: e.target.value as RendererKind });
            }}
          >
            <option value="webgl">WebGL</option>
            <option value="canvas">Canvas</option>
          </select>
        </label>
        <label>
          Contacts
          <select
            value={settings.count}
            onChange={(e) => {
              setSettings({ ...settings, count: Number(e.target.value) });
            }}
          >
            {COUNTS.map((c) => (
              <option key={c} value={c}>
                {c.toLocaleString('en')}
              </option>
            ))}
          </select>
        </label>
        <label>
          Updates per second
          <select
            value={settings.updatesPerSecond}
            onChange={(e) => {
              setSettings({ ...settings, updatesPerSecond: Number(e.target.value) });
            }}
          >
            {UPDATE_RATES.map((u) => (
              <option key={u} value={u}>
                {u.toLocaleString('en')}
              </option>
            ))}
          </select>
        </label>
        <label>
          GPU hit detection (WebGL)
          <input
            type="checkbox"
            checked={settings.gpuHitDetection}
            onChange={(e) => {
              setSettings({ ...settings, gpuHitDetection: e.target.checked });
            }}
          />
        </label>
        <div className="spike-buttons">
          <button type="button" onClick={load} disabled={running}>
            Load
          </button>
          <button type="button" onClick={run} disabled={running || !setupInfo} data-testid="run">
            Run 20 s benchmark
          </button>
        </div>
        <p role="status" data-testid="status">
          {status}
        </p>
        <p className="spike-fps" data-testid="live-fps">
          {liveFps === undefined ? '- fps' : `${String(liveFps)} fps`}
        </p>
        <h2>Results</h2>
        <textarea readOnly value={table} aria-label="Results as Markdown" data-testid="results" />
      </div>
      <div ref={mapRef} className="spike-map" />
    </div>
  );
}
