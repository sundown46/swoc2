# CLAUDE.md — SWOC2 (SEDAP Web Operated C2)

This is the entry point for Claude Code. Read it completely at the start of every session.
It is intentionally short. The details are in `docs/`.

## What SWOC2 is

SWOC2 is a web-based Command & Control (C2) HMI. Its core is a **2D tactical map** that shows a
**shared live operational picture**. The picture is built from SEDAP-Express and, via plugins, from
other sources (AIS, ADS-B, CoT, native SEDAP, ...). All users see the same picture but have
**individual views** (map extent, layers, filters, symbol settings, panel layout, replay time).
Operators edit contacts, chat, draw and share graphic plans, and task own units with SEDAP-Express
COMMAND messages. **One SWOC2 instance = one operational picture / one exercise.**

Read these before writing code:

| File | Purpose |
|---|---|
| `docs/REQUIREMENTS.md` | What to build. Every requirement has an ID (e.g. `MAP-012`) and a phase tag. |
| `docs/ARCHITECTURE.md` | How it is built: modules, data model, protocols, config, deployment. |
| `docs/ROADMAP.md` | Phases and acceptance criteria. **Work only on the current phase** unless told otherwise. |
| `docs/OPEN_QUESTIONS.md` | Unresolved points. Add new ones here instead of guessing silently. |
| `docs/icd/` | SEDAP-Express ICD (Markdown). **Authoritative** for all SEDAP message formats. |
| `docs/adr/` | Architecture Decision Records. Write one for every significant decision. |
| `docs/TESTING.md` | Manual test guide for a human tester, one section per phase's acceptance criteria. Keep it current: a PR that makes a "pending" section testable must update it. |

## Non-negotiable principles

1. **Robustness first.** Never trust input from network connections, admins, users, files or plugins.
   Validate at every boundary. One malformed message, file or failing plugin must never crash the
   process, a connection or the UI. Log it (application log and debug console), drop or degrade
   gracefully, and continue.
2. **Plugins never break the core.** Plugins use only the public SDKs
   (`frontend/packages/plugin-sdk`, `backend/swoc2-plugin-api`). Core code never imports plugin code.
   Each plugin UI is wrapped in an error boundary. Each backend plugin call is isolated with an
   exception barrier and a timeout. Plugins can be disabled in the admin dashboard.
3. **Dogfood the SDK.** Any feature that is not the map core, the live picture, auth or core admin
   should be built as an internal plugin against the same SDK (chat panel, video dashboard, tasking, ...).
4. **Offline-capable.** No CDNs, no external fonts and no implicit internet calls. All assets are bundled.
   Features that use external APIs (e.g. master-data image providers) must degrade gracefully when offline.
5. **SI units and UTC internally.** Lengths in metres, speeds in m/s, time as UTC `Instant`.
   Positions are WGS84 decimal degrees. Angles are degrees relative to true north, normalised to [0, 360).
   Convert only at the UI edge via the shared `units` / `coords` modules. Never convert inline.
6. **The server is authoritative** for the operational picture. Clients are views.
7. **API-first.** Every UI capability is available through the documented REST and realtime API
   (OpenAPI). The UI has no private backdoor. This is what enables AI agents later.
8. **Runs anywhere.** HTTPS is the recommended mode (via reverse proxy or built-in TLS). Plain HTTP
   must still work for local and offline use, and features that need a secure context degrade
   gracefully (GEN-012). SWOC2 runs behind a reverse proxy (including on a sub-path), as a Docker
   container, in an LXC (plain JAR + systemd) and later on Kubernetes. All configuration comes from
   environment variables (see ARCHITECTURE §12).
9. **Restricted networks, internet-facing.** SWOC2 is reachable from the internet via a public reverse
   proxy, so the hardening in ARCHITECTURE §17 is mandatory. Service computers behind corporate proxies
   (port 443 only, often no WebSockets) are **not priority 1**. However, the transport fallback is built
   in from the start (ARCHITECTURE §6), and nothing may be designed in a way that blocks this path later.
   Full validation happens in P3.
10. **Audit and RBAC.** Audit every state-changing action (who, what, when, before/after).
    Check roles server-side on every endpoint. Never log secrets or tokens.

## Tech stack (summary; details and rationale in ARCHITECTURE)

- **Backend:** Java (current LTS), Spring Boot (current stable) + Spring Modulith, Maven multi-module.
  SEDAP-Express via the reference library `io.github.uniity-team:sedapexpress`.
- **DB:** PostgreSQL + PostGIS + TimescaleDB, migrations with Flyway.
- **Frontend:** React + TypeScript (strict) + Vite, pnpm workspace. OpenLayers (2D), milsymbol,
  Mantine (UI and theming), dockview (dockable panels), Zustand (state), TanStack Query (server state),
  zod (runtime validation). Geodesy uses GeographicLib and MGRS uses the NGA mgrs libraries
  (the same libraries in Java and JS, so results match).
- **Auth:** Keycloak via OIDC using the **backend-for-frontend** pattern (session cookie). Hosted
  setups use one realm per SWOC2 instance on a shared Keycloak. Offline setups use the
  `bundled-keycloak` compose profile.
  There are no tokens in the browser.
- **Realtime:** own envelope protocol over WebSocket, with SSE and HTTPS long-polling as fallbacks.
- **Packaging:** one Docker image (the backend serves the SPA, so there is a single port) and
  docker-compose. Helm comes later.

In Phase 0, check the current stable versions and pin them. Record them in
`docs/adr/0001-technology-versions.md`. Ask before adding heavy dependencies, and record every new
framework-level dependency in an ADR.

## Repository layout (target)

```
/CLAUDE.md
/docs/                      requirements, architecture, roadmap, ADRs, ICD
/backend/                   Maven multi-module
  swoc2-domain/             canonical model, units, geodesy, MGRS (no Spring)
  swoc2-plugin-api/         public backend SPI (semver, stable)
  swoc2-sedap/              SEDAP-Express codec + mapping to domain
  swoc2-app/                Spring Boot application (Modulith modules per feature)
  plugins/                  backend plugins (ais, adsb, cot, ...)
/frontend/                  pnpm workspace
  apps/web/                 the SPA
  packages/plugin-sdk/      public frontend plugin SDK
  packages/units/           unit + coordinate formatting/parsing (shared, fully tested)
  plugins/                  frontend plugins (video, chat, tasking, 3d, matrix, ...)
/deploy/
  docker/                   Dockerfile(s)
  compose/                  docker-compose.yml (prod-like; profiles: bundled-keycloak, public-proxy),
                            docker-compose.dev.yml (+ dev Keycloak, MediaMTX, simulator)
  keycloak/                 realm export (shared by dev and bundled-keycloak)
  README.md                 operator guide incl. realm-per-instance setup
  lxc/                      systemd unit, install notes
/tools/                     dev utilities (e.g. SEDAP test sender), not part of the product
```

## Commands

Keep this section up to date whenever commands change.

- Backend build + tests: `./backend/mvnw -f backend/pom.xml verify`
- Frontend: `pnpm -C frontend install`, `pnpm -C frontend dev`, `pnpm -C frontend build`,
  `pnpm -C frontend test`, `pnpm -C frontend lint`, `pnpm -C frontend typecheck`
- Dev stack: `docker compose -f deploy/compose/docker-compose.dev.yml up -d`
- Full image: `docker build -f deploy/docker/Dockerfile -t swoc2:dev .`

## Coding conventions

- **Language:** all code, comments, docs and UI text in English.
- **Comments explain why.** Every public class, interface, function and module gets a short doc
  comment. Protocol handling cites the ICD section (e.g. `// ICD §5.3 COMMAND, field 7`).
  Leon must be able to navigate the code easily.
- **Java:** records for DTOs and value objects, constructor injection only, no checked-exception leaks
  across module boundaries, `Optional` only as a return type. Module boundaries are enforced by
  Spring Modulith / ArchUnit tests.
- **TypeScript:** `strict: true`, no `any` (use `unknown` and parse with zod at boundaries).
  No business logic in components: put it in hooks, stores or packages.
- **Errors:** REST errors as `application/problem+json` (RFC 9457) with a stable `type` URI.
  The UI shows readable messages, never stack traces.
- **Validation:** Jakarta Bean Validation (backend) and zod (frontend) for every external input.
- **UI:** light and dark theme via tokens only (no hard-coded colours). Every feature must work in
  both themes, at least with mouse and touch, and at sizes from laptop to large touch monitor.
- **Tests:** unit tests are mandatory for parsers, codecs, unit and coordinate conversion, geodesy,
  routing and loop prevention, and aging. Integration tests use Testcontainers (Postgres, MQTT broker).
  UI end-to-end tests with Playwright for critical flows from Phase 2 on. Load and soak tests are
  done with external tools and are not part of the product.

## Git workflow (Claude Code works autonomously)

- Never commit directly to `main`. Branch names: `feat/<area>-<topic>`, `fix/<area>-<topic>`,
  `chore/...`, `docs/...`
- Use Conventional Commits and reference requirement IDs, e.g.
  `feat(map): MGRS grid aggregation with identity counts (MAP-014)`.
- Make small, coherent commits and push regularly. Open a PR with `gh pr create` that contains a
  summary, the requirement IDs, test notes and screenshots for UI changes.
- **Auto-merge** (squash) is allowed when CI is green, **except** for PRs labelled `needs-review`.
  Apply that label to every PR that changes auth or security, DB schema in a non-additive way,
  the public plugin SDK or SPI, the realtime protocol, or the SEDAP wire format. Those PRs wait for Leon.
- Never force-push to `main`. Never rewrite pushed history on shared branches. Never commit secrets:
  `.env` is gitignored, and `.env.example` documents every variable.

## Definition of done

- Build, tests, lint and typecheck pass locally and in CI.
- New behaviour is covered by tests. Docs are updated: requirement status, ARCHITECTURE if the
  structure changed, and an ADR if a decision was made.
- New env vars are added to `.env.example` and ARCHITECTURE §12.
- Works in light and dark mode, over plain HTTP, behind a reverse proxy on a sub-path, and with the
  WebSocket-blocked fallback transport (where the feature is in scope for restricted mode).
- No new errors in the browser console. Failure paths have been tried (bad input, connection loss).

## When unsure

- **ICD ambiguous:** implement the most tolerant reasonable parser (accept, warn, keep the raw value),
  and document the interpretation in `docs/icd/NOTES.md`.
- **Requirement unclear:** do not guess silently. Add the question to `docs/OPEN_QUESTIONS.md`,
  pick the smallest reversible option and mention it in the PR.
- **Prefer boring, well-maintained libraries** over clever custom code, except where ARCHITECTURE
  explicitly requires custom code (realtime protocol, symbol atlas).
