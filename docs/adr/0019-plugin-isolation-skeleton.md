# 0019 - Plugin SDK/SPI skeleton and isolation mechanics

Date: 2026-10-03
Status: Proposed (ROADMAP P0 item 10)

## Context

PLG-001..003 and ARCHITECTURE §8 require plugins that cannot break the core: error boundaries in
the frontend, an exception barrier, timeout and circuit breaker in the backend, and runtime
disabling. P0 item 10 asks for SDK skeletons with one example plugin each that proves this.

## Decision

**Keep the public surface minimal until real features need more.** Both SDKs are versioned
(`SDK_VERSION` / `Swoc2Plugin.SPI_VERSION` = `0.1`); plugins built for another version are refused
at load time. Until P1 is done the version may change with the `needs-review` label; after that it
follows semver.

Backend (`swoc2-plugin-api`, host in `io.swoc2.app.plugins`):
- SPI: `Swoc2Plugin` (id, name, version, lifecycle, `enabledByDefault`), `PluginContext`
  (id, logger), `PluginEndpoint`, `ScheduledTask`. `ConnectionType`, `MessageAdapter`,
  `EnrichmentProvider` and `RuleType` are added with the P1 features that use them, not designed in
  the abstract now.
- Discovery via `ServiceLoader`; plugins are a **runtime-scope** dependency of `swoc2-app`, so core
  code cannot compile against them (CLAUDE.md principle 2). Broken jars, invalid/duplicate ids and
  SPI mismatches are logged and skipped; boot continues.
- Every call (start/stop, endpoint, scheduled run) goes through `PluginInvoker`: a virtual thread,
  a timeout (default 5 s, cancel + interrupt on expiry), every `Throwable` caught (also `Error`s),
  and a simple circuit breaker: **3 consecutive failures -> state `FAILED`** (any success resets
  the count). An admin re-enables it (`POST /api/plugins/{id}/enable`). Endpoints and tasks are
  read once per enable, so routing never counts as a call.
- Endpoints under `/api/plugins/{id}/endpoints/{path}`; the core does auth, role check (role
  hierarchy), problem+json mapping (502 failed / 504 timeout / 503 disabled / 404 unknown) and
  audit logging of state changes and POST calls (proper audit module in P1).

Frontend (`@swoc2/plugin-sdk`, host in `apps/web/src/plugins`):
- `definePlugin({manifest, contributes: {panels, toolbar}, activate})`; build-time registration in
  `installedPlugins.ts` (PLG-004).
- Each panel renders inside `PluginBoundary` (placeholder with Reload / Disable plugin); toolbar
  handlers and `activate()` run behind try/catch (sync throws and rejected promises). Plugins can
  be switched off at runtime; their contributions disappear.

Example plugins: backend `swoc2-plugin-example` (off by default, `SWOC2_PLUGINS_ENABLED=example`)
with `hello` / `crash` / `hang` endpoints and a tick task; frontend `@swoc2/plugin-example` with a
panel that can crash itself and a throwing toolbar handler.

## Consequences

- A slow-but-not-failing plugin can still use a virtual thread per call until its timeout; a
  per-plugin concurrency limit is added if that ever shows up.
- "Consecutive across all calls" means a healthy background task can mask a failing endpoint. Good
  enough for the skeleton; per-call-type counters can come with plugin metrics (ADM-008, P2).
- Plugin enable/disable state is in memory: a restart applies `SWOC2_PLUGINS_*` again. Persisting
  admin choices comes with the admin dashboard (P1/P2).
