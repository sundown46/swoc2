# 0003 - Modular monolith with Spring Modulith

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 must run as a single deployable unit in constrained environments (offline LXC, a single
Docker container) while still growing many feature areas (auth, picture, connections, routing,
realtime, master data, plans, tasking, chat, alerts, history, video, admin, settings, plugins,
diag - ARCHITECTURE §3) without them turning into a tangled ball of mutual dependencies.

## Decision

Build SWOC2 as a **modular monolith**: one deployable Spring Boot application (`swoc2-app`),
with each feature area implemented as a Spring Modulith module (one Java package per module).
Module boundaries are enforced by Spring Modulith's own verification and ArchUnit tests, not by
convention alone. Inter-module communication goes through application events (using the
Modulith event publication registry for reliability) or explicit module APIs - modules never
reach into each other's internals.

Extensibility for things outside the core comes from the plugin SDKs (CLAUDE.md principle #2,
ADR 0008), not from splitting the backend into microservices.

Rationale (ARCHITECTURE §16, D-002): simple operations (one process to run in an LXC or
container, or offline) while still keeping enforced internal boundaries as the codebase grows.

## Consequences

- No distributed-systems concerns (service discovery, network partitions between modules,
  distributed transactions) for the core application.
- Horizontal scaling of individual feature areas is not possible; see ADR 0010 for why the
  whole backend stays a single replica.
- A module boundary violation fails CI (Spring Modulith / ArchUnit tests), not just code review.
- Because there is only one module (`io.swoc2.app`) so far, the verification test itself is
  added once a second feature module exists (ROADMAP P1), not as part of this skeleton.
