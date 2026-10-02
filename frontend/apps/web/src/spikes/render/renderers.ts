import Feature from 'ol/Feature';
import Point from 'ol/geom/Point';
import type BaseLayer from 'ol/layer/Base';
import VectorLayer from 'ol/layer/Vector';
import WebGLVectorLayer from 'ol/layer/WebGLVector';
import { fromLonLat } from 'ol/proj';
import VectorSource from 'ol/source/Vector';
import Icon from 'ol/style/Icon';
import Style from 'ol/style/Style';

import type { AtlasEntry, SymbolAtlas } from '../../map/symbols/SymbolAtlas';
import type { SymbolRenderOptions } from '../../map/symbols/SymbolProvider';
import type { SyntheticContact } from './synthetic';

/**
 * The two contact renderers under test (ADR 0006/0007, NFR-001). Both draw every contact from
 * the same {@link SymbolAtlas}: WebGL samples it as one texture, Canvas blits sub-rectangles of
 * it. Neither ever renders a symbol per contact.
 */
export interface ContactRenderer {
  readonly kind: 'webgl' | 'canvas';
  readonly layer: BaseLayer;
  /** Applies a batch of position/state changes (one throttled server delta, ARCHITECTURE §6). */
  update(contacts: readonly SyntheticContact[]): void;
  dispose(): void;
}

export interface RendererContext {
  readonly atlas: SymbolAtlas;
  readonly atlasCanvas: HTMLCanvasElement;
  readonly symbolOptions: SymbolRenderOptions;
  /**
   * WebGL only: OpenLayers' GPU hit detection renders a second picking buffer and reads it back
   * with readPixels, which stalls the GPU pipeline. Off by default in the spike; hook/select can
   * use a CPU spatial index instead (see the spike ADR).
   */
  readonly gpuHitDetection: boolean;
}

function entryFor(ctx: RendererContext, contact: SyntheticContact): AtlasEntry | null {
  return ctx.atlas.entry(contact.sidc, { ...ctx.symbolOptions, stale: contact.stale });
}

function makeFeatures(
  ctx: RendererContext,
  contacts: readonly SyntheticContact[],
  withAtlasProperties: boolean,
): Feature<Point>[] {
  return contacts.map((c) => {
    const feature = new Feature(new Point(fromLonLat([c.lon, c.lat])));
    feature.setId(c.id);
    const entry = entryFor(ctx, c);
    if (withAtlasProperties && entry) {
      // Flat properties the WebGL style expressions read (atlas sub-rectangle + anchor).
      feature.setProperties(
        {
          ax: entry.x,
          ay: entry.y,
          aw: entry.width,
          ah: entry.height,
          anx: entry.anchorX,
          any: entry.anchorY,
        },
        true,
      );
    } else {
      feature.set('entry', entry, true);
    }
    return feature;
  });
}

function applyUpdates(
  source: VectorSource<Feature<Point>>,
  contacts: readonly SyntheticContact[],
): void {
  for (const c of contacts) {
    source
      .getFeatureById(c.id)
      ?.getGeometry()
      ?.setCoordinates(fromLonLat([c.lon, c.lat]));
  }
}

/** WebGL renderer: one WebGLVectorLayer, icons sampled from the atlas texture. */
export function createWebGlRenderer(
  ctx: RendererContext,
  contacts: readonly SyntheticContact[],
): ContactRenderer {
  const source = new VectorSource<Feature<Point>>({ features: makeFeatures(ctx, contacts, true) });
  const pixelRatio = ctx.symbolOptions.pixelRatio;
  const layer = new WebGLVectorLayer({
    source,
    disableHitDetection: !ctx.gpuHitDetection,
    style: {
      'icon-src': ctx.atlasCanvas.toDataURL(),
      'icon-offset': ['array', ['get', 'ax'], ['get', 'ay']],
      'icon-size': ['array', ['get', 'aw'], ['get', 'ah']],
      'icon-anchor': ['array', ['get', 'anx'], ['get', 'any']],
      'icon-anchor-x-units': 'pixels',
      'icon-anchor-y-units': 'pixels',
      // The atlas is rendered at device resolution; draw it back at CSS size.
      'icon-scale': 1 / pixelRatio,
    },
  });
  return {
    kind: 'webgl',
    layer,
    update: (batch) => {
      applyUpdates(source, batch);
    },
    dispose: () => {
      layer.dispose();
    },
  };
}

/** Canvas renderer: classic vector layer, one cached Icon style per atlas entry. */
export function createCanvasRenderer(
  ctx: RendererContext,
  contacts: readonly SyntheticContact[],
): ContactRenderer {
  const source = new VectorSource<Feature<Point>>({ features: makeFeatures(ctx, contacts, false) });
  const styles = new Map<AtlasEntry, Style>();
  const pixelRatio = ctx.symbolOptions.pixelRatio;
  const layer = new VectorLayer({
    source,
    style: (feature) => {
      const entry = feature.get('entry') as AtlasEntry | null | undefined;
      if (!entry) return undefined;
      let style = styles.get(entry);
      if (!style) {
        style = new Style({
          image: new Icon({
            img: ctx.atlasCanvas,
            offset: [entry.x, entry.y],
            size: [entry.width, entry.height],
            anchor: [entry.anchorX, entry.anchorY],
            anchorXUnits: 'pixels',
            anchorYUnits: 'pixels',
            scale: 1 / pixelRatio,
          }),
        });
        styles.set(entry, style);
      }
      return style;
    },
  });
  return {
    kind: 'canvas',
    layer,
    update: (batch) => {
      applyUpdates(source, batch);
    },
    dispose: () => {
      layer.dispose();
    },
  };
}
