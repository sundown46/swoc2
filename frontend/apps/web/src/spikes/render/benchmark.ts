import OlMap from 'ol/Map';
import View from 'ol/View';
import { fromLonLat } from 'ol/proj';

import { SymbolAtlas, htmlAtlasCanvas } from '../../map/symbols/SymbolAtlas';
import { milsymbolProvider } from '../../map/symbols/milsymbolProvider';
import type { SymbolRenderOptions } from '../../map/symbols/SymbolProvider';
import { frameStats, type FrameStats } from './frameStats';
import {
  createCanvasRenderer,
  createWebGlRenderer,
  type ContactRenderer,
  type RendererContext,
} from './renderers';
import { advance, generateContacts, SYNTHETIC_SIDCS, type SyntheticContact } from './synthetic';

export type RendererKind = 'webgl' | 'canvas';

export interface BenchmarkSettings {
  readonly count: number;
  readonly renderer: RendererKind;
  /** Contact updates per second, delivered in 2 Hz batches like the server throttle (ARCHITECTURE §6). */
  readonly updatesPerSecond: number;
  readonly durationSeconds: number;
  /** WebGL only: keep OpenLayers' GPU hit detection on (costs a readPixels per frame). */
  readonly gpuHitDetection: boolean;
}

export interface BenchmarkResult {
  readonly settings: BenchmarkSettings;
  readonly setupMs: number;
  readonly atlasSymbols: number;
  readonly stats: FrameStats;
  readonly userAgent: string;
  readonly devicePixelRatio: number;
  readonly viewport: string;
}

/** Server delta rate the spike simulates (ARCHITECTURE §6 default: 2 Hz). */
const BATCH_HZ = 2;

/**
 * Drives the rendering spike: builds the synthetic picture, feeds 2 Hz update batches, flies a
 * scripted camera path and records frame intervals. Kept out of React (CLAUDE.md "no business
 * logic in components"); the page only calls these methods.
 */
export class RenderBenchmark {
  private map: OlMap | null = null;
  private renderer: ContactRenderer | null = null;
  private contacts: SyntheticContact[] = [];
  private cursor = 0;
  private updateTimer: number | undefined;
  private liveFrameTimes: number[] = [];
  private liveRaf: number | undefined;

  constructor(private readonly target: HTMLElement) {}

  /** Builds map, atlas and renderer for the given settings; returns setup time and atlas size. */
  setup(settings: BenchmarkSettings): { setupMs: number; atlasSymbols: number } {
    this.teardown();
    const start = performance.now();
    const pixelRatio = window.devicePixelRatio || 1;
    const options: SymbolRenderOptions = {
      size: 24,
      pixelRatio,
      stale: false,
      theme: currentTheme(),
    };
    const atlasCanvas = document.createElement('canvas');
    atlasCanvas.width = 2048;
    atlasCanvas.height = 2048;
    const atlas = new SymbolAtlas(htmlAtlasCanvas(atlasCanvas), [milsymbolProvider]);
    // Pre-render every symbol the generator can emit. With a real picture the atlas grows on
    // demand; the WebGL texture then needs a re-upload (see ADR for this spike).
    for (const sidc of SYNTHETIC_SIDCS) {
      atlas.entry(sidc, options);
      atlas.entry(sidc, { ...options, stale: true });
    }
    const ctx: RendererContext = {
      atlas,
      atlasCanvas,
      symbolOptions: options,
      gpuHitDetection: settings.gpuHitDetection,
    };

    this.contacts = generateContacts(settings.count);
    this.renderer =
      settings.renderer === 'webgl'
        ? createWebGlRenderer(ctx, this.contacts)
        : createCanvasRenderer(ctx, this.contacts);
    this.map = new OlMap({
      target: this.target,
      layers: [this.renderer.layer],
      view: new View({ center: fromLonLat([10, 54]), zoom: 5.5 }),
      controls: [],
    });
    this.startUpdates(settings.updatesPerSecond);
    return { setupMs: Math.round(performance.now() - start), atlasSymbols: atlas.size };
  }

  private startUpdates(updatesPerSecond: number): void {
    const perBatch = Math.round(updatesPerSecond / BATCH_HZ);
    if (perBatch <= 0) return;
    this.updateTimer = window.setInterval(() => {
      const { updated, cursor } = advance(this.contacts, this.cursor, perBatch, 1 / BATCH_HZ);
      this.cursor = cursor;
      this.renderer?.update(updated);
    }, 1000 / BATCH_HZ);
  }

  /**
   * Flies a fixed camera path (zoom in/out and pans over the dense clusters) for the configured
   * duration and measures every frame. The first second is a warm-up and not counted.
   */
  async run(
    settings: BenchmarkSettings,
    setup: { setupMs: number; atlasSymbols: number },
  ): Promise<BenchmarkResult> {
    const map = this.map;
    if (!map) throw new Error('setup() first');
    const view = map.getView();
    const intervals: number[] = [];
    const warmupMs = 1000;
    const endAt = performance.now() + warmupMs + settings.durationSeconds * 1000;
    let last = performance.now();
    let measuring = false;
    window.setTimeout(() => {
      measuring = true;
      last = performance.now();
    }, warmupMs);

    const waypoints: { center: [number, number]; zoom: number }[] = [
      { center: [4, 52.5], zoom: 7.5 },
      { center: [12, 56], zoom: 6 },
      { center: [18, 58], zoom: 8.5 },
      { center: [8, 50], zoom: 5 },
      { center: [10, 54], zoom: 9.5 },
      { center: [2, 49], zoom: 6.5 },
    ];
    let index = 0;
    const flyNext = () => {
      if (performance.now() >= endAt) return;
      const wp = waypoints[index++ % waypoints.length];
      if (!wp) return;
      view.animate({ center: fromLonLat(wp.center), zoom: wp.zoom, duration: 2500 }, flyNext);
    };
    flyNext();

    await new Promise<void>((resolve) => {
      const frame = (now: number) => {
        if (measuring) {
          intervals.push(now - last);
          last = now;
        }
        if (now < endAt) {
          requestAnimationFrame(frame);
        } else {
          view.cancelAnimations();
          resolve();
        }
      };
      requestAnimationFrame(frame);
    });

    return {
      settings,
      setupMs: setup.setupMs,
      atlasSymbols: setup.atlasSymbols,
      stats: frameStats(intervals),
      userAgent: navigator.userAgent,
      devicePixelRatio: window.devicePixelRatio,
      viewport: `${String(this.target.clientWidth)}x${String(this.target.clientHeight)}`,
    };
  }

  /** Live fps for manual panning: average over the last ~60 frames. */
  startLiveFps(onFps: (fps: number) => void): void {
    this.stopLiveFps();
    let last = performance.now();
    let sinceReport = 0;
    const frame = (now: number) => {
      this.liveFrameTimes.push(now - last);
      if (this.liveFrameTimes.length > 60) this.liveFrameTimes.shift();
      last = now;
      sinceReport += 1;
      if (sinceReport >= 15) {
        sinceReport = 0;
        onFps(frameStats(this.liveFrameTimes).avgFps);
      }
      this.liveRaf = requestAnimationFrame(frame);
    };
    this.liveRaf = requestAnimationFrame(frame);
  }

  stopLiveFps(): void {
    if (this.liveRaf !== undefined) cancelAnimationFrame(this.liveRaf);
    this.liveRaf = undefined;
    this.liveFrameTimes = [];
  }

  teardown(): void {
    this.stopLiveFps();
    if (this.updateTimer !== undefined) window.clearInterval(this.updateTimer);
    this.updateTimer = undefined;
    this.renderer?.dispose();
    this.renderer = null;
    this.map?.setTarget(undefined);
    this.map?.dispose();
    this.map = null;
  }
}

function currentTheme(): 'light' | 'dark' {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Markdown table row for pasting results into the spike ADR. */
export function resultRow(r: BenchmarkResult): string {
  const s = r.stats;
  const renderer =
    r.settings.renderer === 'webgl' && r.settings.gpuHitDetection
      ? 'webgl+hit'
      : r.settings.renderer;
  return `| ${renderer} | ${String(r.settings.count)} | ${String(r.settings.updatesPerSecond)}/s | ${String(s.avgFps)} | ${String(s.onePercentLowFps)} | ${String(s.p95FrameMs)} | ${String(s.longFrames)} | ${String(r.setupMs)} ms | ${r.viewport} @${String(r.devicePixelRatio)}x |`;
}
