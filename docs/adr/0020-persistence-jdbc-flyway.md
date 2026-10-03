# 0020 - Persistence: plain JDBC + Flyway on one TimescaleDB-HA image

Date: 2026-10-03
Status: Proposed (P1 M1)

## Context

ADR 0012 fixes PostgreSQL + PostGIS + TimescaleDB as the only database. P1 M1 needs the first
tables (audit log, instance settings) and a way to test against the real database.

## Decision

- **Access:** Spring `JdbcClient` with explicit SQL, records as row types. No JPA/Hibernate: the
  data is small-to-medium, partly JSONB/PostGIS/hypertable-shaped, and explicit SQL keeps
  behaviour visible and reviewable (CLAUDE.md "boring, well-maintained" - JDBC is the most boring
  option). Spring Data JDBC can be added per module later if repetitive CRUD shows up.
- **Migrations:** Flyway, plain SQL files `V<n>__<name>.sql`, never edited after release.
  `V1__core.sql` creates the `postgis` and `timescaledb` extensions (the DB user needs that right,
  or an admin pre-creates them - documented in `deploy/README.md` when the prod compose lands).
- **One image everywhere:** `timescale/timescaledb-ha:pg18.6-ts2.30.2` (PostgreSQL + TimescaleDB +
  PostGIS) in the dev stack (port 5082), Testcontainers and production. ~2.4 GB, pulled once.
- **Tests:** an `ApplicationContextInitializer` registered in the test `spring.factories` starts one
  shared container per test JVM, so every `@SpringBootTest` gets the real database without
  annotations. Test-only config lives in `src/test/resources/config/application.yml`, which is
  loaded *in addition to* the main `application.yml` (a test `application.yml` would shadow it -
  a real incident while building M1: springdoc settings silently missing in all tests).
- **Availability:** the database is a hard dependency (unlike Keycloak, ADR 0004). Flyway retries
  the connection for ~1 min at startup, then the app exits with a clear error and the container /
  systemd restart policy retries. Running without a database would mean faking audit and settings,
  which CLAUDE.md principle 10 forbids.
- **Audit:** `audit_event` is append-only, enforced by a trigger that rejects UPDATE/DELETE.
  `AuditLog.record(...)` throws if it cannot write, so an action that cannot be audited fails
  (callers run it in the change's transaction).

## Consequences

- SQL is written by hand; each module owns its tables and migrations are reviewed like code.
- CI pulls the 2.4 GB image for backend tests (a minute on GitHub runners); acceptable for now.
- `/diag` and `config.json` are unavailable while the app waits for the database at startup.
