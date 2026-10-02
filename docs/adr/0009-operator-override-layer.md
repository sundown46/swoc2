# 0009 - Override layer for operator edits

Date: 2026-10-02
Status: Accepted

## Context

Operators edit descriptive contact fields in the CAC (name, remarks, identity,
classification/dimension, symbol code - PIC-003). The same contact keeps receiving source
updates (e.g. a fresh SEDAP CONTACT message) after the edit, and a naive "last write wins"
model would silently discard the operator's edit on the next update.

## Decision

Resolve every descriptive field through a fixed, field-level precedence order (ARCHITECTURE
§5.2): **operator override** (if set) wins over the **source value** (if present), which wins
over the **master-data value** (by MMSI/ICAO). Kinematics (position, course, speed, time)
always come from the source directly and are never overridable this way. Overrides are stored
separately (who, when), are part of the shared picture, persist in Postgres
(`picture_override`), survive restarts, and are individually resettable.

Rationale (ARCHITECTURE §16, D-008): edits must survive source updates (that is the entire
point of letting an operator correct or annotate a contact), and must remain auditable and
reversible rather than being merged destructively into the source record.

## Consequences

- The override layer is cleared only together with a live-picture wipe (PIC-005), or kept if
  the contact can be re-identified by its stable key (PIC-009) across the wipe.
- Every override write is audited (who, what, when, before/after - CLAUDE.md principle #10),
  not just logged.
- Any new editable contact field must go through this same resolution order; a feature that
  writes directly into "the source value" instead of the override layer would make edits
  disappear on the next update, repeating the problem this ADR exists to prevent.
