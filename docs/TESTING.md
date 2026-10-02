# SWOC2 — Manual test guide

Step-by-step instructions for a **human tester** to verify each phase's ROADMAP.md acceptance
criteria by hand. This complements, not replaces, the automated checks (`./backend/mvnw verify`,
`pnpm test`, CI) - those catch regressions; this guide is for the things only a human in a real
browser can judge (does the login flow actually work end to end, does the map actually render,
does the UI actually degrade the way it's supposed to).

**Keep this file current.** Whenever a PR makes a "⏳ Pending" section below testable, that PR
must also flip it to "✅ Ready to test" and replace the placeholder steps with steps the author
actually ran (not just copied from the ticket). Add a new phase section when `ROADMAP.md` moves
to the next phase; never delete a finished phase's section, so this stays the test record for
the whole project, not just the current phase.

## Status legend

| Symbol | Meaning |
|---|---|
| ✅ | Implemented. Steps below were actually run against a real build before being written down. |
| ⏳ | Not implemented yet. The section documents the *intended* test procedure so it's ready to fill in - don't follow it literally yet, it will fail. |

## 0. Test environment and setup

**Assumption** (per this guide's scope): SWOC2 runs on a headless VPS; you test from a browser
on your laptop, reaching the VPS over SSH port forwarding - no services are exposed on the
public internet during manual testing.

### 0.1 One-time: SSH alias

Add to your laptop's `~/.ssh/config` so every command below can just say `swoc2-vps`:

```sshconfig
Host swoc2-vps
    HostName <your-vps-hostname-or-ip>
    User <your-ssh-user>
    IdentityFile ~/.ssh/<your-key>
```

### 0.2 Port forwarding

Open a dedicated terminal and leave it running for the duration of a test session (`-N` = no
remote command, just forward):

```bash
ssh -N \
  -L 5080:localhost:5080 \
  -L 5443:localhost:5443 \
  -L 5081:localhost:5081 \
  swoc2-vps
```

- `5080` → the SWOC2 app's own port (`SWOC2_HTTP_PORT`, direct, no proxy).
- `5443`/`5081` → reserved for the Caddy reverse-proxy tests (§3.3) and dev Keycloak (§2's future
  steps) once those exist; add more `-L` flags as new services come up. Harmless to forward a
  port nothing is listening on yet.

Everything below assumes this tunnel is up and you open URLs as `http://localhost:<forwarded
port>/...` in your laptop browser.

### 0.3 Prerequisites on the VPS

Already available in this environment (verified while building PR #1): Docker 29.x, a real
Eclipse Temurin JDK 25 and Apache Maven 3.9.9 (via `./backend/mvnw`), and Node.js 24 + pnpm 12.8.1
(via `corepack`). You should not need to install anything extra to follow this guide; if a
command below fails with "command not found", that's a real gap to fix, not something to work
around.

## 1. Phase 0 — Foundation & spikes

Source: `ROADMAP.md` "P0 — Foundation & spikes" → **Acceptance**. Each criterion below is one
of that list's bullets, in the same order.

### 1.1 CI is green and the Docker image builds ✅

Covers: PR #1 (`feat/p0-repo-skeleton-ci-adrs`).

1. Check GitHub Actions directly:
   ```bash
   gh pr checks 1   # or: gh pr checks <PR number> for later PRs
   ```
   **Expected:** `Backend (verify)`, `Frontend (lint, typecheck, test, build)` and
   `Docker image build` all show `pass`.
2. Reproduce the Docker build yourself on the VPS:
   ```bash
   ssh swoc2-vps
   cd /data/projects/swoc2
   git fetch origin
   git checkout main              # or the PR branch you're reviewing
   docker build -f deploy/docker/Dockerfile -t swoc2:test .
   docker run -d --name swoc2-test -p 5080:5080 swoc2:test
   docker logs -f swoc2-test
   ```
   **Expected log output**, ending with (exact lines will vary only in timestamp/PID):
   ```
   Tomcat started on port 5080 (http) with context path '/'
   Started Swoc2Application in ... seconds
   ```
   Press Ctrl+C to stop following logs (the container keeps running).
3. From your **laptop browser** (tunnel from §0.2 must be up): open `http://localhost:5080/`.
   **Expected:** HTTP 200, a plain page with the heading **"SWOC2"** and the text "SEDAP Web
   Operated C2 - repository skeleton." (This is Spring Boot's default static-resource serving of
   the built SPA's `index.html` - there is no real UI yet, that's expected at this phase.)
4. Clean up:
   ```bash
   docker rm -f swoc2-test
   docker rmi swoc2:test
   ```

### 1.2 Login works with dev Keycloak over plain HTTP and behind Caddy, also on a sub-path ⏳

Covers: ROADMAP P0 items 4 (SPA/`config.json`/base-path serving) and 5 (BFF login, dev
Keycloak realm, role-protected endpoints). Neither exists yet.

Once implemented, this section will cover, as separate numbered checks:
1. Plain HTTP, root path (`http://localhost:5080/`): login redirects to the dev Keycloak login
   page, a test user (see §3.4) can log in, and lands back on the SWOC2 UI with their role's
   features visible (e.g. an Operator sees edit controls, a Viewer does not).
2. The same, but behind Caddy on a sub-path (see §3.3 for the proxy setup) - the login
   redirect, the session cookie, and every asset/API URL must keep working with
   `SWOC2_BASE_PATH` set to something other than `/`.
3. A role-protected test endpoint rejects a logged-in user whose role doesn't have access
   (expect `403`, `application/problem+json`, no stack trace - CLAUDE.md "Errors").

**Do not mark this ✅ until all three have actually been run against a real dev Keycloak**, not
just code-reviewed.

### 1.3 The 100k spike reaches NFR-001 with WebGL; Canvas fallback is documented ⏳

Covers: ROADMAP P0 item 7 (Spike A). Not implemented yet.

Once implemented:
1. Load the synthetic 100k-contact generator against the map.
2. With WebGL available (default browser): pan and zoom continuously for ~30s while watching
   the browser's FPS counter (Chrome DevTools → Rendering → "Frame Rendering Stats", or
   `chrome://tracing`). **Expected:** ≥ 30 fps sustained (NFR-001), no visible stutter on
   contact updates.
3. Disable WebGL (see §3.2) and reload. **Expected:** the map still renders (Canvas fallback),
   and the ADR/spike writeup (`docs/adr/` - number TBD when this lands) states the measured fps
   and any degraded behaviour (e.g. earlier aggregation, RNM-003).
4. Record actual numbers from both runs in the spike's ADR, not just "it felt smooth."

### 1.4 The realtime spike works in all three transport modes, incl. WS blocked ⏳

Covers: ROADMAP P0 item 8 (Spike B) and `docs/realtime-protocol.md` (to be written). Not
implemented yet.

Once implemented, run the same check three times, forcing a different transport each time (see
§3.1 for how), confirming via the Network tab each time that the *expected* transport is the one
actually carrying traffic:
1. WebSocket (default, nothing blocked).
2. SSE + HTTPS POST (WS blocked at the proxy).
3. HTTPS long-polling (WS **and** SSE blocked, or forced via the UI/compose profile).

**Expected** in all three: the live picture still updates, a gap in `seq` triggers a resync, and
switching networks mid-session (e.g. toggling the WS-blocking profile while connected) falls back
without the user having to reload the page.

### 1.5 All ICD message types round-trip ⏳

Covers: ROADMAP P0 item 9 (Spike C), `swoc2-sedap`. Not implemented yet - also blocked on
`docs/OPEN_QUESTIONS.md` Q-010 (the reference library isn't resolvable from Maven Central yet).

Once implemented, this is primarily an **automated** check (`./backend/mvnw -pl swoc2-sedap
test`, round-trip tests built from ICD examples per `docs/icd/`), not a manual one. The manual
part: pick a handful of message types from `docs/icd/SEDAP-Express-ICD-for-AI-v1.4.8.md`
(at least one CONTACT, one OWNUNIT, one COMMAND, one with a deliberately malformed/unknown
field) and confirm by hand, via the debug console (DBG-001, once it exists) or test logs, that:
- Well-formed messages decode to the expected domain object and re-encode byte-for-byte (or
  field-for-field, where the ICD allows reordering).
- The malformed one is accepted tolerantly (warned, raw value kept) rather than dropped or
  crashing the connection (SDX-001, CLAUDE.md principle #1).

### 1.6 A crashing example plugin is contained ⏳

Covers: ROADMAP P0 item 10 (plugin SDK skeletons + one trivial example plugin each). Not
implemented yet.

Once implemented:
1. **Backend:** enable the example backend plugin, then trigger its deliberate crash (however
   the example is built to do so - e.g. an admin-triggerable test endpoint). **Expected:** the
   exception barrier catches it, the app keeps running, the plugin is shown as unhealthy/disabled
   in plugin health (ADM-008), and no other connection or module is affected.
2. **Frontend:** enable the example frontend plugin's panel/contribution, trigger its deliberate
   crash. **Expected:** that contribution's error boundary shows a "plugin failed" placeholder
   with a reload button; the rest of the UI (other panels, the map) keeps working.
3. Disable the plugin from the admin dashboard (ADM-008, once it exists) and confirm its
   contribution disappears cleanly with no leftover errors in the console.

## 2. Phase 1 — Live picture MVP

Not started. Add this section's subsections (one per P1 acceptance bullet, same pattern as
Phase 0 above) when P1 work begins - don't pre-write them speculatively, `ROADMAP.md` may still
change before then.

---

## 3. How-to appendix

Reusable techniques referenced from the phase sections above. These are written so they're
correct and actionable **today** even though most of the features they'll be used to test are
not built yet (marked per-item below).

### 3.1 Forcing each realtime transport (WS / SSE / long-poll)

Status: ⏳ the realtime protocol itself doesn't exist yet (§1.4). This records how it's meant to
be forced, per `ARCHITECTURE.md` §6, so whoever builds it can wire exactly this up.

**Intended end-state (per ARCHITECTURE §6):**
- The client negotiates automatically (tries WS, falls back on failure/timeout, periodically
  retries the better transport).
- A per-user setting lets you **force** a transport instead of negotiating. Once that setting
  exists, this is the primary way to test each mode: open Settings → Realtime, pick
  `WebSocket` / `SSE` / `Long-polling`, reload.

**Infra-level alternative (needed either way for the "WS blocked" acceptance check in §1.4,
since that must prove the *fallback itself* works, not just the forced setting):** ROADMAP P0
item 8 calls for "a compose profile that blocks WebSocket upgrades" - this is the authoritative
test setup once it exists (`docker compose -f deploy/compose/docker-compose.dev.yml --profile
ws-blocked up`, or similar - exact profile name TBD when it's built). Until then, for ad hoc
testing once some transport exists, you can block WS at a reverse proxy in front of it with a
Caddy snippet like:

```caddy
# Caddyfile snippet: reject WebSocket upgrades, forcing SSE/long-poll fallback
@websocket {
    header Connection *Upgrade*
    header Upgrade websocket
}
respond @websocket 426
```

To additionally force long-polling (block SSE too), also block any request whose `Accept`
header is `text/event-stream`:

```caddy
@sse {
    header Accept text/event-stream
}
respond @sse 426
```

**Confirming which transport is actually active** (works regardless of which method above you
used): open your browser's DevTools → Network tab, filter by `WS` to see WebSocket frames
directly; for SSE, look for a long-lived request of type `eventsource`; for long-polling, you'll
see short-lived `GET /rt/poll?after=...` requests repeating roughly every ~25s (per
ARCHITECTURE §6's "~25s hold"). Once `/diag` (GEN-010) exists it will also just tell you the
active transport and render mode directly - prefer that once it's there.

### 3.2 Disabling WebGL to test the Canvas fallback

Status: ✅ this technique works today in any browser, independent of SWOC2's own state; it's
listed here because §1.3 (Spike A) needs it.

**Firefox (simplest, a real supported preference):**
1. Navigate to `about:config`, accept the warning.
2. Search for `webgl.disabled`, set it to `true`.
3. Reload the page under test. Set back to `false` (or search `webgl.disabled` → right-click →
   Reset) to restore normal WebGL.

**Chrome / Chromium (command-line flag, not a runtime toggle):** close all Chrome windows
first, then relaunch from a terminal with WebGL off:
```bash
google-chrome --disable-webgl --disable-webgl2
```
Chrome's `chrome://flags` has no reliable "disable WebGL" toggle - don't rely on one; the launch
flag above is the supported way to actually turn it off for a real test (as opposed to just
throttling GPU rasterization, which looks similar but isn't the same thing).

**Confirming it worked:** visit `https://get.webgl.org/` (needs internet - skip if testing fully
offline, GEN-002) or check `about:support` (Firefox) / `chrome://gpu` (Chrome) for "WebGL:
unavailable". Once `/diag` (GEN-010) exists, it reports this directly for the exact browser SWOC2
will run in - prefer that once available.

### 3.3 Testing the sub-path setup behind Caddy

Status: ⏳ `SWOC2_BASE_PATH` support (ROADMAP P0 item 4) and the Caddy compose example
(`deploy/compose/`, still just a placeholder README) don't exist yet. This records the intended
test procedure.

Once both exist:
1. On the VPS, start the stack with the sub-path example (exact compose file/profile name TBD
   when item 4 lands - check `deploy/compose/README.md` for the current name):
   ```bash
   SWOC2_BASE_PATH=/swoc2/ SWOC2_PUBLIC_URL=http://localhost:5443/swoc2/ \
     docker compose -f deploy/compose/docker-compose.yml up -d
   ```
2. Forward Caddy's port instead of the app's own port - update §0.2's tunnel to map Caddy's port
   (e.g. `-L 5443:localhost:5443`) and open `http://localhost:5443/swoc2/` in your laptop
   browser (**not** `:5080`, which bypasses the proxy and the sub-path entirely).
3. **Expected:** the app loads with every asset, API call and realtime connection resolving
   under `/swoc2/...` - open DevTools → Network and confirm there are no requests going to the
   bare root (`/assets/...` instead of `/swoc2/assets/...` would mean the relative-base build
   (ARCHITECTURE §12) or `SWOC2_FORWARDED_HEADERS` handling is broken).
4. Also repeat §1.2's login check through this same sub-path URL - that's the actual P0
   acceptance criterion; the sub-path loading correctly is a precondition for it, not the whole
   check.

### 3.4 Test users per role

Status: ⏳ no Keycloak realm exists yet (ROADMAP P0 item 5). REQUIREMENTS.md AUTH-002 defines
four roles: **Viewer**, **Operator**, **Commander**, **Admin** (each including everything the
previous one can do, plus more).

Once the dev Keycloak realm export (`deploy/keycloak/`) lands, this table must be replaced with
the **actual** seeded usernames/passwords from that realm import (never invent credentials here
that don't match it):

| Role | Username | Password | Can test |
|---|---|---|---|
| Viewer | _TBD_ | _TBD_ | Read-only: view the picture, chat; no edit/command controls visible. |
| Operator | _TBD_ | _TBD_ | Viewer + edit contacts (CAC overrides), chat, draw/share plans, own contacts. |
| Commander | _TBD_ | _TBD_ | Operator + send COMMANDs to OWNUNITs. |
| Admin | _TBD_ | _TBD_ | Commander + the admin dashboard (users, connections, wipe, audit log). |

Dev-only credentials (never reuse these conventions for a hosted/production realm).

---

## Changelog

- **2026-10-02** - Initial version, written against PR #1 (`feat/p0-repo-skeleton-ci-adrs`,
  ROADMAP P0 items 1-3). Only §1.1 (CI green, Docker image builds) is actually testable today;
  every other Phase 0 criterion is marked ⏳ pending items 4-10. Commands in §1.1 and the
  WebGL-disabling technique in §3.2 were run against a real build before being written down;
  everything marked ⏳ is a procedure to fill in later, not something that has been tried.
- **2026-10-02** - Renumbered every port in this guide: `8080`→`5080` (app),
  `8443`→`5443` (Caddy sub-path test), `8081`→`5081` (reserved for dev Keycloak), because
  `8080`/`8443`/`8081` are already in use by other services on the VPS. `SWOC2_HTTP_PORT`'s
  default changed to match (`.env.example`, `application.yml`, `ARCHITECTURE.md` §12,
  `deploy/docker/Dockerfile`'s `EXPOSE`) - re-verified §1.1's commands end to end against the new
  port before updating this file.
