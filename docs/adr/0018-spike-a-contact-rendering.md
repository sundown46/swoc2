# 0018 - Spike A: contact rendering with OpenLayers WebGL + symbol atlas

Date: 2026-10-03
Status: Accepted (2026-10-03, after Leon's laptop measurement)

## Context

NFR-001: with WebGL on a mid-range laptop, 100k contacts at >= 30 fps while panning/zooming, no
visible stutter on updates. ADR 0006/0007 chose OpenLayers WebGL layers and a symbol atlas; ROADMAP
P0 item 7 requires measuring that before feature work.

## What was built

- `frontend/apps/web/src/map/symbols/`: the `SymbolProvider` contract (ARCHITECTURE §9), the
  milsymbol provider, and `SymbolAtlas` (shelf-packed texture atlas; each unique (provider, code,
  size, pixel ratio, stale/live, theme) is rendered once; full/unsupported/failing provider ->
  fallback, never throws). These are meant to stay as the map core's building blocks.
- `frontend/apps/web/spike-render.html` (`src/spikes/render/`): benchmark page. Deterministic
  synthetic picture (10k/50k/100k contacts, 96 SIDCs x live/stale, 60 % in dense clusters),
  updates delivered in 2 Hz batches like the server throttle (0 / 2k / 10k updates per second),
  WebGL (`ol/layer/WebGLVector`, icons sampled from the atlas via per-feature offset/size/anchor
  expressions) or Canvas (`ol/layer/Vector`, one cached `Icon` per atlas entry), a scripted 20 s
  camera flight, frame statistics (avg fps, 1 % low, p95 frame time, frames > 50 ms) and live fps
  for manual panning. Results come out as Markdown rows for this ADR.

## Results so far (headless, not representative)

Measured on the VPS with headless Chromium 153 (Playwright). There is **no GPU**: WebGL runs on
SwiftShader (software). The WebGL numbers therefore say nothing about NFR-001. Canvas runs on the
CPU as it would on a laptop, so its numbers are at least indicative.

| Renderer | Contacts | Updates | avg fps | 1 % low fps | p95 frame ms | frames > 50 ms | setup | viewport |
|---|---|---|---|---|---|---|---|---|
| webgl (SwiftShader) | 10000 | 2000/s | 8 | 4.8 | 183.3 | 157 | 213 ms | 1248x900 @1x |
| webgl (SwiftShader) | 100000 | 10000/s | 0.9 | 0.6 | 1566.6 | 14 | 497 ms | 1248x900 @1x |
| canvas | 10000 | 2000/s | 44.3 | 14.6 | 50 | 29 | 78 ms | 1248x900 @1x |
| canvas | 100000 | 10000/s | 10.8 | 1.9 | 366.7 | 97 | 497 ms | 1248x900 @1x |

### Laptop measurement (Leon, 2026-10-03)

| Renderer | Contacts | Updates | avg fps | 1 % low fps | p95 frame ms | frames > 50 ms | setup | viewport |
|---|---|---|---|---|---|---|---|---|
| webgl | 100000 | 10000/s | 73 | 30.1 | 14.1 | 0 | 465 ms | 810x964 @1x |
| canvas | 100000 | 10000/s | 24.2 | 4.7 | 106.7 | 111 | 303 ms | 810x964 @1x |

**NFR-001 is met with WebGL** (>= 30 fps also in the 1 % low, no frame over 50 ms). Canvas is
noticeably stuttery and collapses when zoomed far out (everything in view), confirming that
Canvas mode needs early aggregation (RNM-003, MAP-014). Not measured yet: labels (next risk; the
label layer with decluttering, ARCHITECTURE §9, gets measured with this page in P1), the
0-updates and GPU-hit-detection variants.

## Findings

- **Symbol atlas works:** 192 entries, all symbols render correctly in both renderers from one
  atlas bitmap (screenshots in the PR). Setup of 100k contacts < 0.5 s.
- **GPU hit detection costs a `readPixels` per frame** (Chrome logs "GPU stall due to ReadPixels").
  Off by default in the spike (`disableHitDetection`). Proposal: hook/select (MAP-0xx) via a CPU
  spatial index on click instead of GPU picking. The page has a checkbox to measure the difference.
- **Canvas at 100k is far below 30 fps** even with cached icon styles, so Canvas mode needs early
  server-side aggregation (RNM-003, MAP-014). That matches the planned design; the threshold
  should come from the laptop measurement.
- **Update cost risk (WebGL):** `WebGLVectorLayer` rebuilds the buffers of the whole source on any
  feature change (in a worker). With 2 Hz batches that is a full 100k rebuild twice a second. If
  the laptop run shows stutter on updates, mitigations in order: (1) split contacts into several
  sources/layers by MGRS cell so an update rebuilds only one chunk; (2) a custom WebGL points
  layer with partial buffer updates (ARCHITECTURE allows custom code only where needed, so only if
  (1) is not enough).
- **Dynamic atlas growth:** with WebGL the atlas is uploaded as an `icon-src` data URL. When new
  symbols appear, the style must be reset (`layer.setStyle`), which re-uploads the texture. The
  spike pre-renders all symbols; the real map must batch atlas growth (e.g. at most once per
  second) to avoid repeated uploads.
- **Bundle size:** the spike entry is 1.37 MB (ol + milsymbol, 349 kB gzip). Code splitting
  is a P1 concern (Vite chunk warning).

## Decision

Keep ADR 0006/0007 as decided: OpenLayers WebGL vector layer + symbol atlas, Canvas fallback with
early aggregation, CPU-side picking (GPU hit detection off). Labels are measured with the same
benchmark page when the label layer is built (P1).
