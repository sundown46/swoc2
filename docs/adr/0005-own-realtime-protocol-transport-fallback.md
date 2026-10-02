# 0005 - Own realtime protocol with WS/SSE/long-poll fallback

Date: 2026-10-02
Status: Accepted

## Context

The live picture, chat and tasking must update in near-real-time for every connected browser
(PIC-001, CHT-001, TSK-007), but SWOC2 must also work through corporate proxies that allow only
port 443 and sometimes block WebSocket upgrades entirely (GEN-014, RNM-001). This has to be
true from day one, not bolted on later, even though full validation of the restricted-network
path is deferred to P3 (CLAUDE.md principle #9).

## Decision

Define SWOC2's own realtime envelope protocol (`{v, seq, type, payload}`, documented in
`docs/realtime-protocol.md`, written as part of Spike B) behind **one transport-agnostic
interface** with three interchangeable implementations: WebSocket (primary), SSE for downstream
plus HTTPS POST for upstream, and HTTPS long-polling as the last resort. The client negotiates
automatically (tries WS, falls back on failure or timeout, periodically retries the better
transport) and the user can force a transport in settings (ARCHITECTURE §6).

Server-side session state (viewport, active layers/filters, mode, render capability) plus
snapshot+delta+seq+resync semantics are the same regardless of which transport carries them.

Rationale (ARCHITECTURE §16, D-004): corporate proxy compatibility has to be a first-class
concern from day one, not a retrofit - retrofitting transport fallback onto a WS-only design
later would mean redesigning the session/delta model under pressure.

## Consequences

- Every realtime feature must be designed against the envelope/session abstraction, never
  directly against a WebSocket API, or the fallback transports silently stop working for it.
- Spike B (ROADMAP P0 item 8) must prove this with a compose profile that blocks WebSocket
  upgrades before any feature work depends on realtime updates.
- Binary encoding can be added behind the same interface later if profiling demands it; JSON is
  the only encoding for now (ARCHITECTURE §6).
