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
  -L 6445:localhost:6445 \
  -L 6446:localhost:6446 \
  swoc2-vps
```

- `5080` → the SWOC2 app's own port (`SWOC2_HTTP_PORT`, direct, no proxy).
- `6443` → the dev Caddy sub-path test (§3.3).
- `6445` / `6446` → the same Caddy sub-path proxy, but blocking WebSocket (`6445`) or
  WebSocket and SSE (`6446`), to test the transport fallback (§3.1).
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
4. **Unauthenticated API vs. browser navigation.** Use a private/incognito window (there is no
   logout button yet; logout is `POST /logout` with the CSRF header, which the P1 UI will call -
   `GET /logout` is a 404 because there is no generated logout page).
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

### 1.3 The 100k spike reaches NFR-001 with WebGL; Canvas fallback is documented ✅

Covers: ROADMAP P0 item 7 (Spike A), ADR 0018. Measured by Leon on a laptop on 2026-10-03
(results in ADR 0018). The measurement has to happen on a machine with a GPU; the VPS has none
(headless WebGL there is software-rendered and meaningless).

1. Build and run the image as in §1.2 (login needed), or without a backend:
   ```bash
   cd /data/projects/swoc2/frontend && pnpm install && pnpm --filter @swoc2/web build
   cd apps/web && pnpm exec vite preview --port 5173
   ```
   and add `-L 5173:localhost:5173` to the SSH tunnel.
2. On the **laptop**, in Chrome (fullscreen, nothing else running): open
   `http://localhost:5173/spike-render.html` (or `http://localhost:5080/spike-render.html` after
   login).
3. For each row: choose the settings, press **Load**, wait for "Loaded ...", then **Run 20 s
   benchmark** and don't touch the mouse.
   - WebGL, 100,000, 10,000 updates/s (the NFR-001 case)
   - WebGL, 100,000, 0 updates/s (render cost only)
   - WebGL, 100,000, 10,000 updates/s, GPU hit detection on
   - Canvas, 100,000, 10,000 updates/s
   - Canvas, 10,000, 2,000 updates/s

   **Expected for NFR-001:** WebGL 100k/10k rows show avg fps >= 30 and few frames > 50 ms.
4. Also pan/zoom by hand for ~30 s with WebGL 100k/10k: watch the live fps and judge whether
   there is visible stutter when updates arrive (every 0.5 s).
5. Copy the Results box and paste the rows into ADR 0018's table, with the laptop model/GPU and
   browser version. Check `chrome://gpu` that WebGL is hardware-accelerated.
6. Check light and dark mode (the page follows the OS setting).

### 1.4 The realtime spike works in all three transport modes, incl. WS blocked ✅

Covers: ROADMAP P0 item 8 (Spike B), `docs/realtime-protocol.md`, backend `io.swoc2.app.realtime`,
frontend `apps/web/src/realtime/`. Test page: `spike-realtime.html` (behind login). It subscribes
to the synthetic `demo` topic (500 moving contacts, 2 Hz deltas).

1. Build and start the app and the dev stack as in §1.7 step 1, **with** the OIDC variables from
   §1.2 so login works (`SWOC2_OIDC_ISSUER_URI`, `_CLIENT_ID`, `_CLIENT_SECRET`), then recreate
   Keycloak once so it imports the new redirect URIs for 6445/6446:
   `docker compose -f deploy/compose/docker-compose.dev.yml up -d --force-recreate keycloak caddy`.
2. **WebSocket:** `http://localhost:6443/swoc2/spike-realtime.html`, log in as `viewer1`.
   **Expected** after a few seconds: Status `connected`, Transport `websocket`, Demo contacts `500`,
   Updates/s about `200`, Last seq increasing, Gaps `0 / 0`.
3. **WS blocked:** same page via `http://localhost:6445/swoc2/spike-realtime.html`. **Expected:**
   Transport `sse`, otherwise as step 2. The console shows the browser's own
   "WebSocket ... 403" (the proxy refusing the upgrade).
4. **WS and SSE blocked:** via `http://localhost:6446/swoc2/...`. **Expected:** Transport
   `long-poll`, otherwise as step 2.
5. **Gap/resync:** on any of them press **Simulate gap** (drops one envelope client-side).
   **Expected:** Gaps/resyncs `1 / 1`, contacts stay `500`, updates continue.
6. **Force a transport:** the selector at the top forces WS/SSE/long-poll on the direct port
   (`http://localhost:5080/spike-realtime.html`); each must reach `connected`.
7. **Network switch / reconnect:** while connected via WebSocket, run `docker restart swoc2-test`
   on the VPS. **Expected:** Status goes to `connecting`/`offline`, then back to `connected`
   within ~30 s without reloading the page (a new session is created, contacts back to 500).
8. In DevTools → Network you can see the active transport: `WS` filter (frames), an `eventsource`
   request, or repeating `rt/poll` requests.

Automated coverage: backend `RealtimeSessionTest` (seq, replay, resync-required, transport
replacement, one-shot polls, heartbeats), `RealtimeHttpTests` (login, CSRF, session ownership,
problem+json), `RealtimeTransportIntegrationTests` (real WS/SSE/long-poll end to end, XSRF cookie);
frontend `RealtimeClient.test.ts` (negotiation, fallback, timeouts, gaps, duplicates, session loss,
forced transport, upgrade). Verified manually on the VPS with headless Chromium behind the
WS-blocking proxies, with a real Keycloak login (2026-10-03).

### 1.5 All ICD message types round-trip ✅

Covers: ROADMAP P0 item 9 (Spike C), `backend/swoc2-sedap`, SDX-001, ADR 0017. This is an
**automated** check; the manual part is reading what it proves.

1. Run the codec tests (JDK 25, see CLAUDE.md for the toolchain path on the VPS):
   ```bash
   ./backend/mvnw -f backend/pom.xml -pl swoc2-sedap -am test
   ```
   **Expected:** `BUILD SUCCESS`, about 350 tests in `swoc2-sedap`:
   - `IcdSamplesTest`: every sample line of the ICD (in
     `swoc2-sedap/src/test/resources/icd-samples.txt`) decodes with **exactly** the listed
     warnings, and re-encodes to the identical line. The few samples that are malformed in the ICD
     itself are marked there and explained in `docs/icd/NOTES.md`.
   - `RoundTripTest`: for every message type, every COMMAND type (53) and every GRAPHIC shape (12),
     a message with every field filled is built, encoded, decoded, and yields the same values with
     no warnings.
   - `ReferenceLibraryConformanceTest`: the same lines go through the reference library
     `sedapexpress` 1.4.8, and every value both sides expose must agree. Known library defects
     (GRAPHIC two-digit types, camera mode spelling) are asserted as *still present*.
   - `ToleranceTest`: malformed input (bad numbers, out-of-range latitude, garbage header, unknown
     names/types, extra fields, invalid BASE64, overlong lines, a zip bomb, random mutations of
     every sample) never throws. Invalid fields stay raw with a warning and the rest is usable.
   - `CommandSchemasTest`: the declarative COMMAND schema covers every CmdType of ICD §6.8, every
     lat has a lon in the same pick group, and weapon/destructive commands are flagged for
     confirmation.
2. Spot-check by hand: pick any line in `icd-samples.txt`, change one value (e.g. a latitude to
   `95`) and re-run `-Dtest=IcdSamplesTest`. **Expected:** that line now fails with a warning on
   exactly that field, and the message is still decoded.
3. Debug-console view of decode warnings (DBG-001) comes in P1. Until then the warnings are only
   visible in the tests.

### 1.6 A crashing example plugin is contained ✅

Covers: ROADMAP P0 item 10, PLG-001..003, ADR 0019. Example plugins: backend
`backend/plugins/example-plugin`, frontend `frontend/plugins/example`.

1. Build the image as in §1.2 and start it with the example plugin switched on (it is off by
   default): add `-e SWOC2_PLUGINS_ENABLED=example` to the `docker run`.
2. **Frontend** - open `http://localhost:5080/` and log in (any test user). Below the heading
   is the plugin shell with a toolbar (*Say hello*, *Throw in handler*), the *Example* panel and the
   list of installed plugins.
   - *Say hello* -> a notification "example: Hello from the example plugin".
   - *Throw in handler* -> a notification "Plugin "Example plugin" failed: ...". Nothing else
     changes; no uncaught error in the console.
   - *Crash this panel* -> the panel is replaced by "Plugin “Example plugin” failed: ..." with
     *Reload* and *Disable plugin*. The toolbar and the rest of the page keep working. *Reload*
     brings the panel back; *Disable plugin* removes all its contributions and the plugin list shows
     "disabled after a crash". The checkbox in the list switches it on again.
3. **Backend** - in the same logged-in browser tab:
   - `http://localhost:5080/api/plugins` -> JSON list with `example`, state `ENABLED`.
   - `.../api/plugins/example/endpoints/hello` -> `{"message":"Hello from the example plugin",...}`.
   - `.../api/plugins/example/endpoints/hang` -> after ~5 s `504` problem+json (timeout).
   - `.../api/plugins/example/endpoints/crash` three times -> `502` problem+json each time, no stack
     trace in the response. Then `/api/plugins` shows state `FAILED`, and `hello` answers `503`.
   - The rest of the app is unaffected: `/config.json`, `/diag` and login keep working.
   - `docker logs swoc2-test` shows "Plugin example disabled automatically after 3 consecutive
     failures".
   - Re-enable as `admin1`: `POST /api/plugins/example/enable` (with the CSRF header; easiest via
     the browser console once #7 is merged:
     `fetch('api/plugins/example/enable',{method:'POST',headers:{'X-XSRF-TOKEN':decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1])}})`).
     A non-admin gets `403`.
4. Light/dark: the placeholder page has no styling yet (P1 brings Mantine); nothing to check.

Automated coverage: `PluginRegistryTest` (crash, `Error`s, timeout, auto-disable, broken/duplicate/
incompatible plugins, failing scheduled task, config overrides), `PluginApiTests` (the real example
plugin over REST), `PluginShell.test.tsx` (render crash, reload, disable, throwing handler, refused
plugins, failing activate). Verified on the VPS in the built image with a real Keycloak login and
headless Chromium (2026-10-03).

### 1.7 `/diag` diagnostics page (GEN-010) ✅

Covers: ROADMAP P0 item 6. Not an acceptance bullet of its own, but the tool later sections use
to check transports and render modes on a given machine (Q-004, Q-009).

1. Build and start the image and the dev Caddy (Keycloak is not needed - `/diag` works without
   login, and without Keycloak even being reachable):
   ```bash
   ssh swoc2-vps
   cd /data/projects/swoc2
   docker rm -f swoc2-test 2>/dev/null   # an older test container would hold port 5080
   docker build -f deploy/docker/Dockerfile -t swoc2:test .
   docker run -d --name swoc2-test --network host swoc2:test
   docker compose -f deploy/compose/docker-compose.dev.yml up -d caddy
   ```
2. **Direct, plain HTTP.** Laptop browser: `http://localhost:5080/diag`. **Expected:** the page
   loads without a login redirect. After a few seconds "Resulting modes" shows transport
   **websocket**, and all four rows under "Network and transports" show **works**. SSE should
   say "Events streamed as sent (spread ~800 ms)", and long-polling should come back after
   ~1500 ms. "Map renderer" is **webgl** on a laptop with a GPU. (Headless/VM browsers show
   **canvas** with WebGL marked **software**, which is the intended result: software WebGL is
   slower than Canvas.)
3. **Behind Caddy, sub-path:** `http://localhost:6443/swoc2/diag`. **Expected:** same as step
   2. Check in DevTools → Network that every request goes to `/swoc2/...`.
4. **WebSocket blocked:** `http://localhost:6445/swoc2/diag`. **Expected:** WebSocket
   **fails**, transport **sse**. The browser console shows exactly one error, the browser's
   own `WebSocket connection ... failed: ... 403`. Browsers always log that and a page cannot
   suppress it. No other console errors.
5. **WebSocket and SSE blocked:** `http://localhost:6446/swoc2/diag`. **Expected:** transport
   **long-poll**.
6. **Copy report:** on `http://localhost:5080/diag` (localhost counts as a secure context),
   "Copy report" copies JSON to the clipboard. To see the plain-HTTP fallback, open the page
   via the VPS's IP/hostname instead of `localhost` (not a secure context): "Secure context"
   shows **no**, the four features show **limited** with their fallback, and "Copy report"
   shows a text box with the JSON to copy by hand (GEN-012).
7. Light and dark mode: switch the OS/browser colour scheme; the page follows it.
8. Clean up: `docker rm -f swoc2-test`,
   `docker compose -f deploy/compose/docker-compose.dev.yml down`.

Automated coverage: `DiagProbeTests` (backend: anonymous access, long-poll cap, SSE event
count, WebSocket echo/message limit/foreign-origin rejection) and `src/diag/*.test.ts(x)`
(frontend: mode resolution, probe success/failure/timeout/buffering detection, UI).

## 2. Phase 1 — Live picture MVP

In progress (plan: `docs/plans/P1.md`). Subsections are added per milestone as things become
testable; the final structure will follow the P1 acceptance bullets in `ROADMAP.md`.

### 2.1 Database, audit log, instance settings, OpenAPI (M1) ✅ (API level)

There is no UI for these yet (admin dashboard is M8); test through the browser with the API.

1. Start the database with the dev stack, then the app with the DB settings:
   ```bash
   docker compose -f deploy/compose/docker-compose.dev.yml up -d db keycloak caddy
   docker rm -f swoc2-test; docker build -f deploy/docker/Dockerfile -t swoc2:test .
   docker run -d --name swoc2-test --network host \
     -e SWOC2_DB_URL=jdbc:postgresql://localhost:5082/swoc2 -e SWOC2_DB_USER=swoc2 \
     -e SWOC2_DB_PASSWORD=swoc2-dev \
     -e SWOC2_OIDC_ISSUER_URI=http://localhost:5081/realms/swoc2-dev \
     -e SWOC2_OIDC_CLIENT_ID=swoc2 -e SWOC2_OIDC_CLIENT_SECRET=dev-only-swoc2-secret \
     -e SWOC2_PLUGINS_ENABLED=example swoc2:test
   docker logs swoc2-test | grep -E "Successfully applied|Started Swoc2"
   ```
   **Expected:** "Successfully applied 1 migration" on first start, then "Started Swoc2Application".
   Without a reachable database the app waits ~1 min and then exits with a clear error.
2. Log in as `admin1` at `http://localhost:5080/`. **Swagger UI:** `http://localhost:5080/api/docs`
   shows the API (settings, audit, plugins, realtime session, ...). As `viewer1` it is `403`; the
   raw description `http://localhost:5080/api/openapi.json` is readable by every logged-in user.
3. **Instance settings:** in Swagger UI, `GET /api/settings/instance` shows the defaults (sender
   ID `SWOC2`, stale 2 min / delete 30 min). `PUT` a changed document (e.g. sender ID `OPS-1`, own
   position 53.5/8.1). **Expected:** `200`; invalid values (sender ID with `;`, latitude 95,
   stale >= delete) give `400` problem+json naming each bad field. Restart the container: the
   saved values are still there.
4. **Audit log:** `GET /api/audit` (admin) lists the settings change with your user name, before
   and after; disabling/enabling the example plugin (`POST /api/plugins/example/disable`) appears
   as `plugin.disable`. Filters: `actor`, `action` (prefix), `from`/`to`, `limit`, `beforeId`.
5. **Security headers:** DevTools → Network → any response has `Content-Security-Policy`
   (`default-src 'self'` ...), `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`. Over plain
   HTTP there is no `Strict-Transport-Security` (by design). The browser console shows no CSP
   violations on `/`, `/diag`, `spike-render.html`, `spike-realtime.html` and `/api/docs`.

### 2.2 SEDAP connections and the live picture (M2 + M3a) ✅ (API level)

Still no map UI (M6); test through Swagger UI (`http://localhost:5080/api/docs`, as `admin1`) and the
JSON endpoints. Setup as §2.1, plus the test sender on the VPS (JDK 17 is enough for it):

```bash
java tools/sedap-sender/SedapSender.java --mode server --port 50001 --contacts 200 --relative --garbage
```

1. **Create a connection:** `POST /api/connections` with
   `{"name":"Simulator","type":"sedap-tcp-client","direction":"BOTH","config":{"host":"127.0.0.1","port":50001}}`.
   `GET /api/connections/types` shows the available types with their form schemas. Invalid input
   (empty name, host `bad host!`, port 70000) gives `400` with `fieldErrors` per field.
2. **Health:** `GET /api/connections` - state `UP`, `messagesInPerSecond` ~200, `lastHeartbeat`
   set, `errors`/`warnings` counting the garbage lines. Stop the sender (Ctrl+C): state `DOWN` with
   "retry in N s"; start it again: back to `UP`, `reconnects` increased.
3. **Picture:** `GET /api/picture/summary` ~201 contacts (incl. the OWNUNIT); `GET
   /api/picture/contacts?limit=5` shows names, identities and positions moving between calls.
4. **Debug console data:** enable it in the settings (`debugConsole: {"enabled":true,"roles":["admin"]}`),
   then `GET /api/debug/messages?limit=50` shows raw lines and the warnings for the bad ones.
5. **Overrides:** `PUT /api/picture/contacts/{id}/override` with `{"name":"Renamed"}` (as
   operator or admin): the name stays "Renamed" although the sender keeps sending the old name;
   `DELETE` the override and the source name is back. Both appear in `GET /api/audit`.
6. **Aging:** set `aging` to `{"staleAfter":"PT10S","deleteAfter":"PT30S"}`, stop the sender:
   contacts turn `STALE` after 10 s and disappear after 30 s.
7. **Wipe:** `POST /api/picture/wipe` with `{"confirm":"WIPE"}` - the picture empties (and refills
   while the sender runs); without the confirmation it is `400`.
8. **Server transport:** create a `sedap-tcp-server` connection on port 50002 and run the sender
   with `--mode client --port 50002`: state goes from `DEGRADED` (no client) to `UP`.

Automated coverage: `SedapConnectionTests` (real TCP: ingest, relative positions, dedup, own
sender, garbage, delete flag, reconnect, server transport, validation, test endpoint, roles,
audit), `PictureStoreTest`, `AgingServiceTest`, `PictureApiTests`, `LineReaderTest`.
### 2.2 Live picture store, aging, overrides, wipe (M2) ⏳

Implemented at API level (`/api/picture/...`, see Swagger UI), but there is no way to put contacts
into the picture by hand until the SEDAP connections and the test sender arrive in M3. Then this
section gets its steps: contacts appear in `GET /api/picture/contacts`, turn `STALE` after the
stale time and disappear after the delete time; an operator renames a contact via
`PUT /api/picture/contacts/{id}/override` and the name survives further updates; an admin wipes
with `POST /api/picture/wipe` and `{"confirm":"WIPE"}`.

Automated coverage: `PictureStoreTest` (identity across updates, cell index, override layer,
stale/remove races, wipe, classification, 200k upserts in well under a second on the VPS),
`AgingServiceTest`, `PictureApiTests` (roles, persistence, audit, validation, wipe confirmation).

---

## 3. How-to appendix

Reusable techniques referenced from the phase sections above. These are written so they're
correct and actionable **today** even though most of the features they'll be used to test are
not built yet (marked per-item below).

### 3.1 Forcing each realtime transport (WS / SSE / long-poll)

Status: the blocking proxies below are ✅ (verified with `/diag`, §1.7); the realtime protocol
that uses them is ⏳ (§1.4).

**Intended end-state (per ARCHITECTURE §6):**
- The client negotiates automatically (tries WS, falls back on failure/timeout, periodically
  retries the better transport).
- A per-user setting lets you **force** a transport instead of negotiating. Once that setting
  exists, this is the primary way to test each mode: open Settings → Realtime, pick
  `WebSocket` / `SSE` / `Long-polling`, reload.

**Infra-level (needed either way for the "WS blocked" acceptance check in §1.4, since that
must prove the *fallback itself* works, not just the forced setting):** the dev Caddy
(`docker compose -f deploy/compose/docker-compose.dev.yml up -d caddy`) proxies the app on the
`/swoc2/` sub-path on three ports:

| Port | Blocks | Expected transport |
|---|---|---|
| `6443` | nothing | WebSocket |
| `6445` | WebSocket upgrades (403) | SSE + POST |
| `6446` | WebSocket upgrades and `Accept: text/event-stream` (403) | long-polling |

See `deploy/compose/Caddyfile.dev`. `/diag` on each port (§1.7) shows which transport works
there.

**Confirming which transport is actually active** (works regardless of which method above you
used): open your browser's DevTools → Network tab, filter by `WS` to see WebSocket frames
directly; for SSE, look for a long-lived request of type `eventsource`; for long-polling, you'll
see short-lived `GET /rt/poll?after=...` requests repeating roughly every ~25s (per
ARCHITECTURE §6's "~25s hold"). `/diag` (§1.7) tells you which transports work from the current
browser and network.

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
- **2026-10-03** - §1.7 `/diag` (GEN-010, P0 item 6) added and ✅. The dev Caddy got two
  restricted-network ports (`6445` WS blocked, `6446` WS+SSE blocked), and §3.1 / §0.2 now
  describe them. The real tunnel/Caddy steps were run against the built image on the VPS with
  headless Chromium (Playwright) in light and dark mode. The ports in that run differed (app on
  5090 next to an existing test container), the Caddy config was the same.
- **2026-10-03** - P0 roll-up: §1.1-§1.7 are all ✅ (§1.4 with PR #7, §1.6 with PR #8). Leon ran
  §1.3 on a laptop (results in ADR 0018) and the other sections except a hand check of SEDAP
  byte-for-byte re-encoding (covered by `IcdSamplesTest`).
