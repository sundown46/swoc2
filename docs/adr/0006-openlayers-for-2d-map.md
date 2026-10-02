# 0006 - OpenLayers for the 2D map

Date: 2026-10-02
Status: Accepted

## Context

The map core must support arbitrary admin-configured WMS/WMTS layers with CRS reprojection
(MAP-004), render up to 100k contacts with smooth pan/zoom (MAP-009, NFR-001), and fall back to
a Canvas renderer when WebGL is unavailable (RNM-003).

## Decision

Use OpenLayers for the 2D map, with WebGL vector layers for contact rendering and an automatic
Canvas fallback when WebGL is missing (ARCHITECTURE §9).

Rationale (ARCHITECTURE §16, D-005): OpenLayers has the best WMS/WMTS/CRS support of the
mainstream web-map libraries, and its WebGL vector layers scale to the contact counts NFR-001
requires.

## Consequences

- Symbol rendering must go through a shared symbol atlas (ADR 0007), not per-contact DOM/SVG
  elements, to hit the 100k-contact performance target on both WebGL and Canvas.
- Spike A (ROADMAP P0 item 7) measures actual fps with both renderers against synthetic 100k
  contacts before any real map feature work starts, and documents the result as part of that
  spike (not retroactively assumed here).
- 3D (Cesium, MAP-020, P4) is a separate plugin that consumes the same subscription API and
  layer configuration, not a replacement for OpenLayers.
