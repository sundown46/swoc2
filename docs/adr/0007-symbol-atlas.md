# 0007 - Symbol atlas instead of per-contact SVG

Date: 2026-10-02
Status: Accepted

## Context

Rendering up to 100k contacts (MAP-009, NFR-001) with each symbol potentially unique in size,
fill/frame, level-of-detail modifiers, theme and state (stale/live) would mean rendering one
SVG/DOM element per contact if done naively, which does not scale to that count at 30 fps.

## Decision

Render each unique combination of `(symbolSet, code, size, fill/frame, LOD modifiers, theme,
state)` exactly once into a dynamic texture atlas, via the `SymbolProvider` interface
(`{id, supports(code), render(code, opts), describe(code)}`), and have contacts reference atlas
entries rather than owning their own rendered symbol (ARCHITECTURE §9). milsymbol is the default
`SymbolProvider` for APP-6/MIL-STD-2525; custom icon sets and other symbol standards (e.g.
police symbols, PLG-005) are additional providers behind the same interface.

Rationale (ARCHITECTURE §16, D-006): this is the only realistic way to hit the 100k-contact
performance target - never render one SVG per contact.

## Consequences

- Any new symbol provider (custom icon sets, other standards) must produce atlas-compatible
  output through the same interface; core map code never special-cases a provider.
- Labels are a separate, declutter-aware layer on top of the atlas-rendered symbols
  (ARCHITECTURE §9), not baked into the atlas entries themselves, so label density can be
  limited independently of symbol rendering.
- Validated empirically by Spike A (ROADMAP P0 item 7), not assumed from this ADR alone.
