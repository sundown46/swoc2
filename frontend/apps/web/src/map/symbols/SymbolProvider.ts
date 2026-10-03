/**
 * Symbol provider contract (ARCHITECTURE §9, ADR 0007). A provider renders one symbol code into a
 * bitmap; the {@link SymbolAtlas} caches every unique result exactly once. Core map code only
 * talks to this interface, never to a concrete symbol library, so custom icon sets and other
 * standards (PLG-005) plug in the same way milsymbol does.
 */

/** Options that change how a symbol looks; part of the atlas cache key. */
export interface SymbolRenderOptions {
  /** Nominal symbol size in CSS pixels (milsymbol "size"). */
  readonly size: number;
  /** Device pixel ratio the bitmap is rendered for (crisp symbols on HiDPI screens). */
  readonly pixelRatio: number;
  /** Stale contacts are drawn differently (ARCHITECTURE §9: "state stale/live"). */
  readonly stale: boolean;
  /** UI theme; dark backgrounds need different outlines. */
  readonly theme: 'light' | 'dark';
}

export interface RenderedSymbol {
  /** The bitmap, at `pixelRatio` resolution. */
  readonly image: HTMLCanvasElement;
  /** Anchor in bitmap pixels: the point that sits on the contact position. */
  readonly anchor: { readonly x: number; readonly y: number };
}

export interface SymbolProvider {
  readonly id: string;
  supports(code: string): boolean;
  render(code: string, options: SymbolRenderOptions): RenderedSymbol;
}
