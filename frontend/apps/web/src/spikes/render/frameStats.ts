/** Summary of a benchmark run's frame timings (NFR-001: >= 30 fps, no visible stutter). */
export interface FrameStats {
  readonly frames: number;
  readonly durationMs: number;
  readonly avgFps: number;
  /** Average fps over the slowest 1 % of frames ("1 % low"): the stutter indicator. */
  readonly onePercentLowFps: number;
  readonly p95FrameMs: number;
  readonly maxFrameMs: number;
  /** Frames longer than 50 ms: visible hitches. */
  readonly longFrames: number;
}

/** Computes {@link FrameStats} from consecutive frame intervals in milliseconds. */
export function frameStats(intervalsMs: readonly number[]): FrameStats {
  const frames = intervalsMs.length;
  if (frames === 0) {
    return {
      frames: 0,
      durationMs: 0,
      avgFps: 0,
      onePercentLowFps: 0,
      p95FrameMs: 0,
      maxFrameMs: 0,
      longFrames: 0,
    };
  }
  const durationMs = intervalsMs.reduce((a, b) => a + b, 0);
  const sorted = [...intervalsMs].sort((a, b) => a - b);
  const at = (q: number) => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))] ?? 0;
  const worst = sorted.slice(Math.floor(sorted.length * 0.99));
  const worstAvg = worst.reduce((a, b) => a + b, 0) / Math.max(1, worst.length);
  const round = (v: number) => Math.round(v * 10) / 10;
  return {
    frames,
    durationMs: Math.round(durationMs),
    avgFps: round((frames * 1000) / durationMs),
    onePercentLowFps: round(1000 / worstAvg),
    p95FrameMs: round(at(0.95)),
    maxFrameMs: round(sorted[sorted.length - 1] ?? 0),
    longFrames: intervalsMs.filter((v) => v > 50).length,
  };
}
