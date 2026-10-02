# 0008 - Canonical domain model; SEDAP-Express is an adapter

Date: 2026-10-02
Status: Accepted

## Context

The live picture must merge contacts from multiple source formats (SEDAP-Express, AIS, ADS-B,
CoT, native SEDAP later - REQUIREMENTS glossary "Source") without the core picture store or the
UI needing to know the wire format a given contact arrived in (MAP-003, PIC-008/009).

## Decision

Define a canonical `Contact` domain model (ARCHITECTURE §5.1) in `swoc2-domain`, with no
dependency on any wire protocol. SEDAP-Express (`swoc2-sedap`) and every other source format
are **adapters** that map onto this model on ingest and map back out on egress; they never
define the model itself. Contact identity is `(connectionId, sourceSystemId, sourceTrackId)`
(PIC-009), stable regardless of which adapter produced it.

Rationale (ARCHITECTURE §16, D-007): multiple source formats must coexist today, and a native
SEDAP adapter (SDX-014) is expected later (P4) - the model cannot be SEDAP-shaped internally or
every new source format becomes a special case in the core.

## Consequences

- `swoc2-domain` has zero Spring and zero protocol dependencies (ARCHITECTURE §3), so it stays
  usable from plugins and simple tools, and so adapters can be tested against it without
  standing up the whole application.
- Adding a new source format (a plugin, PLG-002 `MessageAdapter`) never requires changing the
  domain model; if it seems to, that is a sign the model is missing something genuinely
  cross-format, not something source-specific.
- No automatic fusion across sources for the same real-world object (PIC-008) - that is left to
  a future optional correlation plugin (P4), kept deliberately out of the canonical model.
