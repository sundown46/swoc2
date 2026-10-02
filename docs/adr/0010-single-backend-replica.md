# 0010 - Single backend replica, in-memory picture

Date: 2026-10-02
Status: Accepted

## Context

The live picture (PIC-001) must be identical and server-authoritative for every connected
client, updated at up to 10k updates/s across up to 100k contacts (NFR-003) with sub-500ms p95
latency to clients (NFR-002). SWOC2 must also stay simple to run in an LXC or a single container
(CLAUDE.md principle #8).

## Decision

Keep the live picture **in memory** (concurrent maps plus an MGRS cell index, ARCHITECTURE §10)
in a **single backend replica**. Horizontal scaling of the backend is explicitly out of scope
for now; a Kubernetes deployment (P4, GEN-006) still runs exactly one replica.

Rationale (ARCHITECTURE §16, D-009): simplicity and latency. Keeping one authoritative in-memory
copy avoids a distributed cache-coherence problem (which replica is authoritative for a given
contact, how updates fan out between replicas within the latency budget) that horizontal
scaling would otherwise force onto this design.

## Consequences

- An optional periodic snapshot to Postgres exists only for restart recovery, not for
  multi-replica consistency (ARCHITECTURE §10).
- Throughput and latency targets (NFR-002/003) must be met by a single process; if a future
  deployment genuinely needs more than one replica can provide, that requires revisiting this
  ADR and the picture-store design together, not just adding a load balancer in front of it.
- The Kubernetes Helm chart (P4) is explicitly single-replica; it is a packaging convenience,
  not a path to horizontal scaling.
