import type { SymbolProvider, SymbolRenderOptions } from './SymbolProvider';

/** Where one rendered symbol lives inside the atlas bitmap, in atlas pixels. */
export interface AtlasEntry {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
  readonly anchorX: number;
  readonly anchorY: number;
}

/** Minimal canvas surface the atlas needs; injectable so packing is unit-testable without a DOM. */
export interface AtlasCanvas {
  readonly width: number;
  readonly height: number;
  drawImage(image: CanvasImageSource, x: number, y: number): void;
}

/** Spacing between entries, so texture sampling never bleeds into a neighbour. */
const PADDING = 2;

/**
 * Dynamic texture atlas (ADR 0007): every unique (provider, code, options) combination is
 * rendered once and packed into one bitmap that both the WebGL and the Canvas renderer draw
 * from. Packing is a simple shelf packer: symbols of one size class have near-identical heights,
 * so shelves waste little space.
 *
 * {@link version} increments whenever new entries are drawn; renderers that upload the atlas as a
 * texture (WebGL) re-upload when it changes.
 */
export class SymbolAtlas {
  private readonly entries = new Map<string, AtlasEntry>();
  private shelfX = 0;
  private shelfY = 0;
  private shelfHeight = 0;
  private full = false;
  version = 0;

  constructor(
    private readonly canvas: AtlasCanvas,
    private readonly providers: readonly SymbolProvider[],
    private readonly fallback: AtlasEntry | null = null,
  ) {}

  /** Number of distinct symbols rendered so far. */
  get size(): number {
    return this.entries.size;
  }

  get isFull(): boolean {
    return this.full;
  }

  static key(providerId: string, code: string, options: SymbolRenderOptions): string {
    return `${providerId}|${code}|${String(options.size)}|${String(options.pixelRatio)}|${options.stale ? 's' : 'l'}|${options.theme}`;
  }

  /**
   * Returns the atlas entry for a symbol, rendering it on first use. Returns the fallback entry
   * (or null) if no provider supports the code, rendering fails, or the atlas is full - a broken
   * symbol must never break the map.
   */
  entry(code: string, options: SymbolRenderOptions): AtlasEntry | null {
    const provider = this.providers.find((p) => p.supports(code));
    if (!provider) return this.fallback;
    const key = SymbolAtlas.key(provider.id, code, options);
    const cached = this.entries.get(key);
    if (cached) return cached;
    if (this.full) return this.fallback;
    try {
      const rendered = provider.render(code, options);
      const placed = this.place(rendered.image.width, rendered.image.height);
      if (!placed) {
        this.full = true;
        console.warn(
          `Symbol atlas full after ${String(this.entries.size)} symbols; using fallback symbol`,
        );
        return this.fallback;
      }
      this.canvas.drawImage(rendered.image, placed.x, placed.y);
      const entry: AtlasEntry = {
        x: placed.x,
        y: placed.y,
        width: rendered.image.width,
        height: rendered.image.height,
        anchorX: rendered.anchor.x,
        anchorY: rendered.anchor.y,
      };
      this.entries.set(key, entry);
      this.version++;
      return entry;
    } catch (error) {
      console.warn(`Symbol provider ${provider.id} failed for ${code}`, error);
      return this.fallback;
    }
  }

  private place(width: number, height: number): { x: number; y: number } | null {
    if (width + PADDING > this.canvas.width || height + PADDING > this.canvas.height) return null;
    if (this.shelfX + width + PADDING > this.canvas.width) {
      // New shelf below the current one.
      this.shelfY += this.shelfHeight;
      this.shelfX = 0;
      this.shelfHeight = 0;
    }
    if (this.shelfY + height + PADDING > this.canvas.height) return null;
    const position = { x: this.shelfX, y: this.shelfY };
    this.shelfX += width + PADDING;
    this.shelfHeight = Math.max(this.shelfHeight, height + PADDING);
    return position;
  }
}

/** Wraps a real 2D canvas as an {@link AtlasCanvas}. */
export function htmlAtlasCanvas(canvas: HTMLCanvasElement): AtlasCanvas {
  const context = canvas.getContext('2d');
  if (!context) throw new Error('2D canvas context unavailable');
  return {
    width: canvas.width,
    height: canvas.height,
    drawImage: (image, x, y) => {
      context.drawImage(image, x, y);
    },
  };
}
