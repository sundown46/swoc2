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
  -L 6443:localhost:6443 \
  -L 5081:localhost:5081 \
  swoc2-vps
```

- `5080` → the SWOC2 app's own port (`SWOC2_HTTP_PORT`, direct, no proxy).
- `6443` → the dev Caddy sub-path test (§3.3).
- `5081` → the dev Keycloak (§3.4). Its admin console also lives here
  (`http://localhost:5081/admin`, `admin`/`admin` - dev-only, never reuse that password
  anywhere real).

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

### 1.2 Login works with dev Keycloak over plain HTTP and behind Caddy, also on a sub-path ✅

Covers: ROADMAP P0 items 4 (SPA/`config.json`/base-path serving) and 5 (BFF login, dev
Keycloak realm, role-protected endpoints). There is still no real UI beyond the placeholder
page (that's P1), so "logged in" here is verified via the JSON test endpoints, not by reading
the page - that's enough to prove the actual P0 criterion.

1. Start the dev Keycloak (see §3.4 for what's in the realm) and the app:
   ```bash
   ssh swoc2-vps
   cd /data/projects/swoc2
   docker compose -f deploy/compose/docker-compose.dev.yml up -d keycloak
   # wait ~10s for Keycloak to come up, then from another terminal on the VPS:
   cd backend && ./mvnw -q install -DskipTests   # once, so the reactor siblings resolve
   cd swoc2-app
   SWOC2_OIDC_ISSUER_URI=http://localhost:5081/realms/swoc2-dev \
   SWOC2_OIDC_CLIENT_ID=swoc2 \
   SWOC2_OIDC_CLIENT_SECRET=dev-only-swoc2-secret \
     ../mvnw spring-boot:run
   ```
2. **Plain HTTP, root path.** From your **laptop browser** (tunnel from §0.2 up): open
   `http://localhost:5080/`. **Expected:** redirected to the Keycloak login page at
   `http://localhost:5081/realms/swoc2-dev/...`. Log in as `commander1` / `swoc2dev` (§3.4).
   **Expected:** redirected back to `http://localhost:5080/` with a session cookie set (check
   DevTools → Application → Cookies: `JSESSIONID`, `HttpOnly`). Open
   `http://localhost:5080/api/test/whoami` in the same browser/tab. **Expected:** JSON body
   containing `"username":"commander1"` and `"authorities"` including `"ROLE_COMMANDER"`.
3. **Role hierarchy and role-protected endpoints.** Still as `commander1`:
   `http://localhost:5080/api/test/viewer-or-higher` → **expected** `200`,
   `{"ok":true,...}` (a Commander satisfies a Viewer-level check via the role hierarchy,
   REQUIREMENTS AUTH-002). `http://localhost:5080/api/test/admin-only` → **expected** `403`
   with an `application/problem+json` body (`"status":403,"type":"https://swoc2.example/
   problems/forbidden"`), no stack trace. Repeat as `admin1` - `admin-only` now returns `200`.
4. **Unauthenticated API vs. browser navigation.** Log out
   (`http://localhost:5080/logout`, confirm the logout page) or use a private/incognito window.
   `curl -i http://localhost:5080/api/test/whoami` → **expected** `401`,
   `application/problem+json`. Opening `http://localhost:5080/` in the browser (no `Accept:
   application/json`) still **redirects to login** rather than showing that same 401 - the two
   paths are deliberately handled differently (see `SecurityConfig` for why).
5. **Behind Caddy, on a sub-path.** Stop the app (Ctrl+C) and restart it with
   `SWOC2_BASE_PATH=/swoc2/` added to the env vars from step 1, then also bring up Caddy:
   ```bash
   docker compose -f deploy/compose/docker-compose.dev.yml up -d caddy
   ```
   From your laptop browser, open `http://localhost:6443/swoc2/` (**not** `:5080`, which
   bypasses the proxy entirely) and repeat step 2's login as `viewer1` / `swoc2dev`. **Expected:**
   same result, entirely under the `/swoc2/` prefix throughout - check DevTools → Network that
   the redirect to Keycloak carries `redirect_uri=http://localhost:6443/swoc2/login/oauth2/
   code/swoc2` (not the bare `:5080` URL), and that `http://localhost:6443/swoc2/config.json`
   returns `{"basePath":"/swoc2/"}`.
6. Clean up: `docker compose -f deploy/compose/docker-compose.dev.yml down`.

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

### 3.3 Testing the sub-path setup behind Caddy ✅

The app always answers on `/` internally; `deploy/compose/Caddyfile.dev` strips the `/swoc2`
prefix before forwarding (`handle_path`) and adds `X-Forwarded-Prefix: /swoc2` so Spring's
`ForwardedHeaderFilter` (toggled by `SWOC2_FORWARDED_HEADERS`, on by default) puts the prefix
back onto any URL it generates - in particular the OAuth2 login callback, which is the part
that actually breaks if forwarded-prefix handling is wrong (verified: without it, Keycloak
redirects back to the bare `:5080` URL, which 404s through the proxy).

1. Run the app with `SWOC2_BASE_PATH=/swoc2/` set (see §1.2 step 5 for the full env var list),
   and bring up Caddy:
   ```bash
   docker compose -f deploy/compose/docker-compose.dev.yml up -d caddy
   ```
   `Caddyfile.dev` reaches the app via `host.docker.internal:5080` regardless of whether it's
   running directly on the VPS (`mvnw spring-boot:run`) or as the `swoc2:test` container from
   §1.1 (`-p 5080:5080`) - either way works.
2. Forward Caddy's port (`-L 6443:localhost:6443`, already in §0.2's tunnel) and open
   `http://localhost:6443/swoc2/` in your laptop browser (**not** `:5080`, which bypasses the
   proxy and the sub-path entirely).
3. **Expected:** `http://localhost:6443/swoc2/config.json` returns `{"basePath":"/swoc2/"}`,
   and the full login flow (§1.2 step 5) works entirely under the `/swoc2/` prefix.
4. This dev setup is deliberately plain HTTP, no TLS (GEN-003's "HTTP mode works without
   certificates", exercised by this exact test). The publicly-trusted-certificate reverse proxy
   for locked-down service computers (GEN-014) is a separate, P3 concern - don't conflate the
   two when this section eventually needs a P3 companion.

### 3.4 Test users per role ✅

Seeded by `deploy/keycloak/realm-export.json` (realm `swoc2-dev`), imported automatically when
the `keycloak` service in `docker-compose.dev.yml` starts (`start-dev --import-realm`). Roles
are client roles of the `swoc2` client (REQUIREMENTS AUTH-002); the hierarchy (admin > commander
> operator > viewer, verified in §1.2 step 3) means each user below can also do everything the
rows above it can:

| Role | Username | Password | Can test |
|---|---|---|---|
| Viewer | `viewer1` | `swoc2dev` | Read-only: view the picture, chat; no edit/command controls visible. |
| Operator | `operator1` | `swoc2dev` | Viewer + edit contacts (CAC overrides), chat, draw/share plans, own contacts. |
| Commander | `commander1` | `swoc2dev` | Operator + send COMMANDs to OWNUNITs. |
| Admin | `admin1` | `swoc2dev` | Commander + the admin dashboard (users, connections, wipe, audit log). |

None of the role-gated *features* above exist yet (P1/P2) - only the role mapping itself is
testable right now, via `/api/test/viewer-or-higher` and `/api/test/admin-only` (§1.2).

Dev-only credentials, realm `sslRequired: none`. Never reuse this realm export's secrets or
password convention for a hosted or production instance (ADR 0015).

There's also a `swoc2-invite-service` service account in the realm (AUTH-003's future invite
flow) - not used by any endpoint yet, so there's nothing to manually test about it beyond "the
realm imported without errors" (§1.2 step 1 already proves that, since the app couldn't get an
OIDC discovery document from Keycloak otherwise).

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
- **2026-10-02** - §1.2 (login via dev Keycloak, plain HTTP and behind Caddy on a sub-path),
  §3.3 and §3.4 flipped to ✅: ROADMAP P0 items 4 and 5 are implemented (BFF OIDC login,
  Keycloak client-role → Spring authority mapping with the AUTH-002 hierarchy, role-protected
  test endpoints, `config.json`, forwarded-headers/sub-path handling, the dev Keycloak realm).
  All of §1.2's steps were actually run end to end with curl simulating the full browser
  authorization-code+PKCE flow against a real Keycloak 26.8 container, for all four test users,
  both directly and through the Caddy sub-path - not just read off the code. §3.1 (realtime
  transports) and §1.3-1.6/P0 items 6-10 are still ⏳, unaffected by this change.
- **2026-10-02** - Fixed a real incident reported after the above: a plain `docker run` of the
  image with no Keycloak configured at all (exactly §1.1's own test) crashed the JVM outright
  instead of booting, because Spring Boot's OAuth2 client resolves the Keycloak registration -
  including a live discovery call to the issuer - eagerly at startup (see ADR 0004
  "Consequences"). Fixed by deferring that call to the first real login attempt.
- **2026-10-02** - Second real incident, found immediately after the above: with the boot crash
  fixed, a browser hitting `/` in the same no-Keycloak situation instead got stuck in an
  infinite redirect loop against `/oauth2/authorization/swoc2` (Spring Security's own
  authorization-request filter swallows the resolution failure and falls through to the same
  entry point again). Fixed in the entry point itself: resolve the registration *before*
  redirecting, and render a plain "login unavailable" page (503, no stack trace) when it fails,
  instead of ever issuing the broken redirect. Re-verified both directions against the real
  Docker image: `docker run -p 5080:5080 swoc2:test` with zero env vars now returns a clean 503
  at `/` (`/config.json` still → 200, neither crashes nor loops), and the full §1.2 login flow
  for all four test users still works unchanged once Keycloak is actually up.
- **2026-10-02** - Renumbered the Caddy sub-path test port again: `5443`→`6443`, because `5443`
  turned out to already be in use by AdGuard on the VPS. Only the Caddy port changes - `5080`
  (app) and `5081` (Keycloak) are unaffected. Updated `deploy/compose/Caddyfile.dev`,
  `docker-compose.dev.yml`'s port mapping, and the `swoc2` client's registered redirect URI in
  `deploy/keycloak/realm-export.json` to match.
