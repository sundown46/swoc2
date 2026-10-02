import ms from 'milsymbol';

import type { RenderedSymbol, SymbolProvider, SymbolRenderOptions } from './SymbolProvider';

/**
 * Default provider for APP-6 / MIL-STD-2525 SIDCs (ARCHITECTURE §9) via milsymbol. Never throws
 * for bad input: an invalid SIDC renders milsymbol's "unknown" frame, which is the honest thing
 * to show for a contact whose symbol we cannot interpret (CLAUDE.md principle 1).
 */
export const milsymbolProvider: SymbolProvider = {
  id: 'milsymbol',

  supports(code: string): boolean {
    // Letter-based SIDCs (2525B/C, APP-6A/B) are 15 chars, number-based (2525D/E, APP-6D) 20/30.
    return /^[A-Za-z*-]{10,15}$/.test(code) || /^\d{20}(\d{10})?$/.test(code);
  },

  render(code: string, options: SymbolRenderOptions): RenderedSymbol {
    const symbol = new ms.Symbol(code, {
      size: options.size,
      // Stale contacts: unfilled frame, so they are distinguishable without colour alone.
      fill: !options.stale,
      outlineWidth: options.theme === 'dark' ? 2 : 0,
      outlineColor: options.theme === 'dark' ? 'rgb(16,18,22)' : undefined,
    });
    const image = symbol.asCanvas(options.pixelRatio);
    const anchor = symbol.getAnchor();
    return {
      image,
      anchor: { x: anchor.x * options.pixelRatio, y: anchor.y * options.pixelRatio },
    };
  },
};
