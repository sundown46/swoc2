# 0001 - Technology versions

Date: 2026-10-02
Status: Accepted

## Context

CLAUDE.md requires that Phase 0 "check the current stable versions and pin them" before feature
work starts, recorded in this ADR. Versions below were checked against the project's own release
pages, Maven Central / npm registry metadata, or an upstream GitHub release list on 2026-10-02,
and were validated by actually building the P0 repo skeleton with them (see "Validation" below),
not just by reading changelog pages.

## Decision

### Backend

| Component | Version | Notes |
|---|---|---|
| Java | 25 (LTS) | Current LTS, released 2025-09-16, supported until 2033. |
| Maven | 3.9.9 (wrapper) | Maven 4 is still RC-only (`4.0.0-rc-7`); not used yet. |
| Spring Boot | 4.1.1 | Current stable; built on Spring Framework 7.0.8. |
| Spring Modulith | 2.1.1 | The release line compatible with Spring Boot 4.1.x. |
| SEDAP-Express reference library | `io.github.uniity-team:sedapexpress` 1.4.8 | Matches the
  bundled ICD (`docs/icd/SEDAP-Express-ICD-for-AI-v1.4.8.md`). Published on Maven Central
  (confirmed 2026-10-02 via `maven-metadata.xml`, see Q-010). Wired into `swoc2-sedap` with
  Spike C (ROADMAP P0 item 9). |
| Flyway | 12.4.0 (managed by Spring Boot) | Migrations in `swoc2-app/src/main/resources/db/migration`. |
| Testcontainers | 2.0.5 (managed by Spring Boot) | Integration tests against the real DB image. |
| HiveMQ MQTT Client | 1.4.0 | SEDAP-Express MQTT transport (P1 plan D2). Test/dev broker image `eclipse-mosquitto:2.1.2-alpine`. |
| springdoc-openapi | 3.1.1 | Generated OpenAPI + bundled Swagger UI (API-001); Leon approved 2026-10-03. |
| GeographicLib-Java | 2.1 | For geodesy (ARCHITECTURE §3). |
| NGA MGRS (Java) | `mil.nga:mgrs` 2.1.3 | Same NGA library family as the JS package, so results
  match across backend and frontend (CLAUDE.md tech stack). |
| Database image | `timescale/timescaledb-ha:pg18.6-ts2.30.2` | PostgreSQL 18.6 + TimescaleDB 2.30.2 + PostGIS 3.6.4 in one image; used by the dev stack, Testcontainers and production (ADR 0020). |
| PostgreSQL | 18 | PostgreSQL 19 is in beta; stays on 18 until 19 is GA and PostGIS/Timescale
  confirm support. |
| PostGIS | 3.6 | 3.7 is still pre-release. |
| TimescaleDB | 2.30 | |
| Keycloak | 26.8 (dev / bundled-keycloak images) | Keycloak has no LTS; pin the current release
  and bump deliberately. |

### Frontend

| Component | Version | Notes |
|---|---|---|
| Node.js | 24 (Active LTS) | |
| pnpm | 12.8.1 | pnpm 12 rewrote the CLI in Rust; pinned via `packageManager` (Corepack). |
| React / ReactDOM | 19.3.0 | |
| TypeScript | **6.0.3**, not 7.0 | See "TypeScript 7.0" under Open items/deviations. |
| Vite | 8.3.2 | |
| Vitest | 5.0.3 | |
| Playwright | 1.63.0 | Not installed yet; added with the first E2E test (ROADMAP P2). |
| ESLint | 10.11.0 (flat config only, `eslint.config.js`) | |
| typescript-eslint | 8.71.0 | |
| Prettier | 3.9.9 | |
| OpenLayers (`ol`) | 10.10.0 | Installed with Spike A (ADR 0018). |
| milsymbol | 3.0.4 | Installed with Spike A (ADR 0018). |
| MGRS (JS, `mgrs` on npm, proj4js community package) | 2.2.0 | Not installed yet. The
  NGA-maintained `@ngageoint/mgrs-js` is stale (last published 2023); re-check before adoption. |
| Mantine | 9.6.3 | Not installed yet; added with the first real UI (ROADMAP P1). |
| dockview / dockview-react | 8.4.0 | Not installed yet. |
| Zustand | 5.0.15 | Not installed yet. |
| TanStack Query (`@tanstack/react-query`) | 5.104.1 | Not installed yet. |
| zod | 4.6.5 | Installed with Spike B (realtime client, PR #7). |

Packages marked "not installed yet" are pinned here for later use but are **not** in any
`package.json` yet, because nothing in the P0 repo skeleton uses them (CLAUDE.md: don't add
dependencies ahead of the code that needs them). Add them, at this version unless something
newer and stable has shipped by then, when the feature that needs them is built.

## Open items / deviations

- **TypeScript 7.0 is out but not used.** TypeScript 7.0 (the Go-native compiler rewrite) is the
  actual current stable release, but `typescript-eslint` 8.71.0 declares
  `peerDependencies.typescript: ">=4.8.4 <6.1.0"` and fails outright on 7.0
  ("`typescript-eslint` does not support TS 7.0"), confirmed by actually running `pnpm lint`.
  Decision: pin TypeScript to **6.0.3** (the latest pre-7.0 release) for now. Revisit once
  typescript-eslint ships TS 7 support (tracked upstream as
  `typescript-eslint/typescript-eslint#10940`) - re-run `pnpm lint` after bumping and only merge
  the bump once it's clean, don't assume compatibility from changelogs alone.
- **`io.github.uniity-team:sedapexpress`** was first reported as "not resolvable" because the
  Maven Central *search API* returned zero hits on 2026-10-02. The artefact itself is there
  (`https://repo1.maven.org/maven2/io/github/uniity-team/sedapexpress/maven-metadata.xml` lists
  1.3.0-1.4.8). Lesson: verify Maven Central coordinates against `repo1.maven.org` metadata, not
  only the search API. Its POM pulls in BouncyCastle, Gson, Protobuf, Paho MQTTv5, jssc and
  native-lib-loader; which of those SWOC2 keeps or excludes is recorded with Spike C.
- **Maven itself** stays on the 3.9.x line; Maven 4 is not GA.

## Validation

Pinned versions were not just copied from changelogs; the P0 repo skeleton that introduced this
ADR was actually built and tested with them before merging:

- Backend: `./backend/mvnw verify` run end-to-end on a real JDK 25 (Temurin 25.0.4.1), covering
  all four modules, Spotless formatting check, and the Spring Boot context-loads test. Required
  bumping `palantir-java-format` past 2.67.0 (fails on JDK 25 with
  `NoSuchMethodError: ...Log$DeferredDiagnosticHandler.getDiagnostics()`) to 2.100.0, which works.
- Frontend: `pnpm install`, `pnpm lint`, `pnpm typecheck`, `pnpm test` and `pnpm build` run
  end-to-end on real Node.js 24.21.0 and pnpm 12.8.1 across all four workspace packages.

## Consequences

- Revisit this ADR whenever a module that needs a "not installed yet" dependency is built, and
  whenever the TypeScript/typescript-eslint or sedapexpress situation above changes.
- Because Keycloak has no LTS channel, bumping it is a routine maintenance task, not a one-time
  decision - do not wait for an ADR to update it, just keep `deploy/keycloak/` and the dev compose
  profile in sync with the pinned version.
