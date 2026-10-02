# 0012 - PostgreSQL + PostGIS + TimescaleDB as the only database

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 needs relational storage (users' view state, connections, audit log, master data with
provenance), spatial storage and queries (plans/PostGIS geometries), and time-series storage
with downsampling and retention for picture history and replay (HIS-001). It must also stay
simple to deploy offline, in an LXC or a single Docker Compose stack (CLAUDE.md principle #8).

## Decision

Use a single PostgreSQL instance with the PostGIS and TimescaleDB extensions for all persistent
storage (ARCHITECTURE §10), migrated with Flyway. No separate relational, spatial or
time-series database products.

Rationale (ARCHITECTURE §16, D-011): one system covers all three storage shapes the product
needs, instead of operating and backing up three different database products for a single
deployable instance.

## Consequences

- History storage (`track_point` hypertable) and operational tables (users, connections, audit,
  master data, plans) share one PostgreSQL server; capacity planning and backup strategy
  (`deploy/README.md`) must account for both workloads on the same instance.
- The bundled Keycloak profile (AUTH-008) uses its own database "on the same PostgreSQL server"
  (ARCHITECTURE §2) rather than a separate database engine, for the same offline-simplicity
  reason.
- A future need for a genuinely different storage shape (e.g. a dedicated search index) would
  be an addition alongside Postgres, not a replacement, unless this ADR is revisited.
