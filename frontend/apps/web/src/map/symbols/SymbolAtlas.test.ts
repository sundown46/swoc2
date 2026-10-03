import { describe, expect, it, vi } from 'vitest';

import { SymbolAtlas, type AtlasCanvas } from './SymbolAtlas';
import type { SymbolProvider, SymbolRenderOptions } from './SymbolProvider';

const options: SymbolRenderOptions = { size: 30, pixelRatio: 1, stale: false, theme: 'light' };

function fakeCanvas(width: number, height: number) {
  const draws: { x: number; y: number }[] = [];
  const canvas: AtlasCanvas = {
    width,
    height,
    drawImage: (_image, x, y) => draws.push({ x, y }),
  };
  return { canvas, draws };
}

function provider(width = 40, height = 30): SymbolProvider & { calls: number } {
  const p = {
    id: 'fake',
    calls: 0,
    supports: (code: string) => code.startsWith('S'),
    render: () => {
      p.calls++;
      return {
        image: { width, height } as HTMLCanvasElement,
        anchor: { x: width / 2, y: height / 2 },
      };
    },
  };
  return p;
}

describe('SymbolAtlas', () => {
  it('renders each unique symbol once and caches it', () => {
    const p = provider();
    const atlas = new SymbolAtlas(fakeCanvas(256, 256).canvas, [p]);

    const a = atlas.entry('SFGP', options);
    const b = atlas.entry('SFGP', options);

    expect(a).toBe(b);
    expect(p.calls).toBe(1);
    expect(atlas.version).toBe(1);
  });

  it('treats different options as different entries', () => {
    const atlas = new SymbolAtlas(fakeCanvas(256, 256).canvas, [provider()]);

    atlas.entry('SFGP', options);
    atlas.entry('SFGP', { ...options, stale: true });
    atlas.entry('SFGP', { ...options, theme: 'dark' });

    expect(atlas.size).toBe(3);
  });

  it('packs entries on shelves without overlap', () => {
    const { canvas, draws } = fakeCanvas(100, 100);
    const atlas = new SymbolAtlas(canvas, [provider(40, 30)]);

    atlas.entry('S1', options);
    atlas.entry('S2', options);
    atlas.entry('S3', options);

    expect(draws).toEqual([
      { x: 0, y: 0 },
      { x: 42, y: 0 },
      { x: 0, y: 32 },
    ]);
  });

  it('returns the fallback when full, unsupported or failing - never throws', () => {
    const fallback = { x: 0, y: 0, width: 1, height: 1, anchorX: 0, anchorY: 0 };
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const failing: SymbolProvider = {
      id: 'boom',
      supports: (code) => code === 'BOOM',
      render: () => {
        throw new Error('render failed');
      },
    };
    const atlas = new SymbolAtlas(fakeCanvas(50, 40).canvas, [failing, provider(40, 30)], fallback);

    expect(atlas.entry('S1', options)).not.toBe(fallback);
    expect(atlas.entry('S2', options)).toBe(fallback); // no room for a second one
    expect(atlas.isFull).toBe(true);
    expect(atlas.entry('XYZ', options)).toBe(fallback); // unsupported
    expect(atlas.entry('BOOM', options)).toBe(fallback); // provider threw
    warn.mockRestore();
  });
});
