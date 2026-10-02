# 0013 - MGRS-aligned aggregation grid

Date: 2026-10-02
Status: Accepted

## Context

Dense pictures (many contacts in a small area, or a zoomed-out view) need aggregation so the
map stays readable and performant, with per-identity counts shown per cell at a zoom-dependent
cell size (MAP-014). The server also needs an efficient spatial index for the live picture to
meet the throughput targets (NFR-003).

## Decision

Align the aggregation grid to MGRS (Military Grid Reference System) cells, at sizes that follow
zoom (100 km -> 10 km -> 1 km, MAP-014). The picture store's own spatial index is organised by
the same MGRS cell grid (ARCHITECTURE §5.1 "Live picture" storage, §6 "Aggregation mode"), so
the aggregate counts the realtime protocol streams to clients in aggregation mode are a
byproduct of the index SWOC2 already maintains, not a separate computation.

Rationale (ARCHITECTURE §16, D-012): MGRS is the grid military users already think in, and using
it for the spatial index as well means the cell index and the aggregation feature are the same
data structure instead of two.

## Consequences

- The MGRS library choice (ADR 0001: `mil.nga:mgrs` backend, `mgrs` npm package frontend) must
  produce matching cell boundaries on both sides, since the server computes aggregates and the
  client's own coordinate display (MAP-016) must agree with them.
- Changing the aggregation grid shape later (e.g. a non-MGRS grid) would also mean redesigning
  the picture store's spatial index, not just the aggregation feature - the two are coupled by
  this decision.
- Aggregation mode activates based on contact density in the viewport or a configurable zoom
  threshold (lower in Canvas mode, RNM-003), not on a fixed zoom level alone.
