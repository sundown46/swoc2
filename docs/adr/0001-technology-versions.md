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
  bundled ICD (`docs/icd/SEDAP-Express-ICD-for-AI-v1.4.8.md`). **Not actually wired up yet** -
  see "Open items" below and `docs/OPEN_QUESTIONS.md` Q-010. |
| Spring WebSocket (`spring-boot-starter-websocket`) | managed by Spring Boot 4.1.1 | Plain
  servlet WebSocket (no STOMP). Added with `/diag` (GEN-010), and the base for the realtime
  WebSocket transport (ADR 0005). |
| GeographicLib-Java | 2.1 | For geodesy (ARCHITECTURE §3). |
| NGA MGRS (Java) | `mil.nga:mgrs` 2.1.3 | Same NGA library family as the JS package, so results
  match across backend and frontend (CLAUDE.md tech stack). |
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
| OpenLayers (`ol`) | 10.10.0 | Not installed yet; added with Spike A (ROADMAP P0 item 7). |
| milsymbol | 3.0.4 | Not installed yet; added with Spike A. |
| MGRS (JS, `mgrs` on npm, proj4js community package) | 2.2.0 | Not installed yet. The
  NGA-maintained `@ngageoint/mgrs-js` is stale (last published 2023); re-check before adoption. |
| Mantine | 9.6.3 | Not installed yet; added with the first real UI (ROADMAP P1). |
| dockview / dockview-react | 8.4.0 | Not installed yet. |
| Zustand | 5.0.15 | Not installed yet. |
| TanStack Query (`@tanstack/react-query`) | 5.104.1 | Not installed yet. |
| zod | 4.6.5 | Not installed yet. |

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
- **`io.github.uniity-team:sedapexpress` is not resolvable from Maven Central** (checked via the
  Maven Central search API on 2026-10-02: zero results for both the group and the artifact id).
  The version above matches the ICD revision we have, but the real coordinates, the actual
  repository it's published to (Maven Central, GitHub Packages, or none yet), and whether 1.4.8
  is even tagged as a library release rather than just an ICD revision, are unconfirmed. Tracked
  as `docs/OPEN_QUESTIONS.md` Q-010, blocking Spike C (ROADMAP P0 item 9). `swoc2-sedap` is a
  real Maven module already, but does not declare the dependency yet.
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
