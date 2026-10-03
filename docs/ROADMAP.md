# SWOC2 — Roadmap

**Current phase: P1** (set 2026-10-03 on Leon's go; P0 status below)

Claude Code works only on the current phase. A phase is complete when all of its acceptance
criteria are met. Leon then sets the next phase here. Within a phase, the order of items below is
the suggested implementation order.

## P0 — Foundation & spikes

Goal: a solid skeleton and **proof of the risky technical assumptions before feature work starts**.

1. Repo skeleton according to CLAUDE.md: Maven multi-module, pnpm workspace, lint, format and
   typecheck configs, `.editorconfig`, `.gitignore`, `.env.example`.
2. CI with GitHub Actions: backend verify, frontend lint/typecheck/test/build, Docker image build.
3. ADR 0001 with the pinned versions, plus ADRs 0002–0015 created from the ARCHITECTURE decision log (D-001…D-014).
4. Spring Boot app serves the SPA from one port. Runtime `config.json`. `SWOC2_BASE_PATH` and the
   forwarded headers work (tested behind Caddy, also on a sub-path). HTTP mode works without
   certificates.
5. BFF login against Keycloak (dev Keycloak in `docker-compose.dev.yml` with a realm export
   containing the four roles, the SWOC2 client, the invite service account and test users).
   This realm export is the **same artefact** that the `bundled-keycloak` profile uses later.
   Role-protected test endpoints.
6. `/diag` page (GEN-010).
7. **Spike A — rendering:** OpenLayers + symbol atlas + milsymbol, synthetic 100k contacts with
   updates. Measure fps with WebGL and Canvas. Document the results in an ADR (NFR-001).
8. **Spike B — realtime:** transport abstraction WS/SSE/long-poll with snapshot + delta + seq +
   resync. Test with a compose profile that blocks WebSocket upgrades. Write `docs/realtime-protocol.md`.
9. **Spike C — SEDAP:** integrate the reference library. Parse and generate all ICD message types
   with round-trip tests. Prototype the declarative COMMAND schema.
10. Plugin SDK skeletons (frontend and backend) with one trivial example plugin each, proving that
    error isolation works (a deliberately crashing plugin does not affect the app).

**Acceptance** (status 2026-10-03):
- ✅ Login works with dev Keycloak over plain HTTP and behind the Caddy reverse proxy (also on a sub-path).
- ✅ The 100k spike reaches NFR-001 with WebGL. The Canvas fallback behaviour is documented (ADR 0018).
- ✅ (PR #7) The realtime spike works in all three transport modes, including when WS is blocked.
- ✅ All ICD message types round-trip (PR #5).
- ✅ (PR #8) A crashing example plugin is contained.
- ✅ CI is green and the Docker image builds.

Open from P0 requirements, carried into P1: API-001 (OpenAPI), GEN-006 LXC guide, NFR-005.

## P1 — Live picture MVP

Goal: a usable shared picture from SEDAP-Express, with admin basics, in normal and restricted networks.

Requirements: GEN-008, GEN-011, AUTH-003/004/005, MAP-001…013, MAP-016/017/019/021, PIC-001…005,
PIC-008/009, SDX-002/004/005/006, CON-001/002/003/005/006/007, CHT-001…003, TLS-001/003,
ADM-001…006, ADM-009/011, DBG-001/002, RNM-001/004, GEN-012/013/015, AUTH-007/008, PLG-004, API-002.

**Acceptance:**
- Several SEDAP-Express connections (TCP client/server, UDP, MQTT) deliver contacts and OWNUNITs
  into layers per connection. Aging works per connection.
- Invite registration creates new users in a realm-per-instance setup. The `bundled-keycloak` profile
  starts fully offline, and login works under `{base}/auth`. `deploy/README.md` contains the
  realm-per-instance guide.
- Contact list, CAC (with override edits that survive updates), hook/select, distance/bearing and
  own position all work with mouse and touch.
- Symbol and label settings, unit and coordinate formats, and the coordinate input component work.
- WMS/WMTS wizard and offline basemap work.
- The chat panel (dockable, badge) works via TEXT.
- The debug console and the audit log work.
- Everything above, except the debug console, works with WebSockets blocked (transport fallback, RNM-001).
- Security headers, rate limiting and the admin allowlist are active (GEN-015). Secure-context
  degradation works over HTTP (GEN-012).

## P2 — Tasking, plans, video, master data

Requirements: TSK-001…008, SDX-007…010, PLN-001, PLN-003…006, PIC-006/007, MAP-014/015/018,
VID-001…007, MD-001…004, PLG-005, ADM-007/008.

**Acceptance:**
- The full COMMAND palette is available through the guided wizard (including map picking, drafts,
  area tasking and ACK lifecycle).
- Plans can be imported, drawn, styled, shared and sent/received as GRAPHIC.
- Reference points and user-created contacts (sendable) work.
- MGRS grid aggregation with identity counts works.
- The video dashboard works across several MediaMTX servers via the main server. A drone stream from
  STATUS opens from its contact.
- MMSI/ICAO master data is auto-captured and enriches contacts.

## P3 — Operations

Requirements: HIS-001…003, ALR-001…003, TLS-002, STB-001, SDX-011/012, CON-004, EXP-001…003,
MD-005…007, AUTH-006, AUTH-009, GEN-014, RNM-002/003/005. Plugins: AIS, ADS-B, master-data image providers.

**Acceptance:**
- Replay of at least 30 min per user works.
- Recordings can be exported and imported.
- Geofencing rules on plans raise alarms without flooding.
- The nautical tools are available.
- The status board is populated.
- METEO and EMISSION layers are shown.
- Forwarding rules work with loop prevention verified.
- Briefing export works.
- Master data packages can be merged between two instances.
- AIS and ADS-B plugins deliver into their own layers.
- Service-computer path validated: compose profile `public-proxy` (Caddy with SWOC2 + Keycloak under `/auth`
  on one domain) combined with the WS-blocking proxy. Login, map, chat, tasking and HLS video work.
  The Canvas fallback without WebGL works.

## P4 — Extensions

Each item is a plugin or an isolated feature. They are prioritised individually when P3 is done:
3D Cesium (MAP-020), Matrix chat (CHT-004), CoT, multi-point tactical graphics (PLN-007), SEDAP
crypto (SDX-013), native SEDAP adapter (SDX-014), REST and Protobuf transports (SDX-003), GOG import
(PLN-002), police symbol set, runtime plugin loading (PLG-004), MCP / agent bridge + LLM configuration
(API-003, ADM-010), correlation plugin (PIC-008), Helm chart (GEN-006).
