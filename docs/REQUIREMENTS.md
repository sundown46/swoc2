# SWOC2 — Requirements

Each requirement has an ID and a phase tag (`P0`–`P4`, see ROADMAP.md).
Status values: `open`, `in-progress`, `done`. Claude Code updates the status when a requirement is
implemented. Changing the *content* of a requirement needs Leon's approval.

## Implementation status

Only requirements that are not `open` are listed, so the tables below stay untouched.

| ID | Status | Where |
|---|---|---|
| GEN-002 | in-progress | All assets bundled, no CDN/external fonts so far; re-checked with every feature |
| GEN-003 | in-progress | Plain HTTP works end to end; built-in TLS modes are GEN-013 (P1) |
| GEN-004 | done | Sub-path + forwarded headers, tested behind Caddy (TESTING.md §1.2, §1.7) |
| GEN-005 | in-progress | Every setting so far via env vars (`.env.example`, ARCHITECTURE §12) |
| GEN-006 | in-progress | Docker image done; LXC/systemd guide pending (`deploy/lxc/`) |
| GEN-009 | in-progress | Ongoing principle; tolerant codec, transport fallback, plugin isolation so far |
| GEN-010 | done | `/diag` page (`frontend/apps/web/src/diag/`, `io.swoc2.app.diag`), TESTING.md §1.7 |
| AUTH-001 | done | BFF login against Keycloak (ADR 0004), TESTING.md §1.2 |
| AUTH-002 | in-progress | Role mapping + hierarchy done; enforced per feature as features land |
| MAP-009 | in-progress | WebGL 100k proven (ADR 0018); the real map comes in P1 |
| SDX-001 | done | `backend/swoc2-sedap` codec (ADR 0017), `docs/icd/NOTES.md`, TESTING.md §1.5 |
| PLG-001 | in-progress | SDK skeleton (panels, toolbar), PR #8 / ADR 0019; more extension points with P1/P2 |
| PLG-002 | in-progress | SPI skeleton (lifecycle, endpoints, scheduled tasks), PR #8; more SPIs with P1 |
| PLG-003 | in-progress | Isolation proven with example plugins, PR #8 (done once merged) |
| API-001 | done | springdoc: `{base}/api/openapi.json`, Swagger UI `{base}/api/docs` for admins (TESTING.md §2.1) |
| AUTH-005 | in-progress | Audit log infrastructure (`io.swoc2.app.audit`, ADR 0020); picture edits audited when they exist (M2/M7) |
| ADM-003 | in-progress | Instance settings API (sender ID, own position, aging, debug console); UI in M8 |
| ADM-006 | in-progress | Audit query API with filters; viewer UI in M8 |
| GEN-015 | in-progress | CSP + security headers done (M1); rate limiting, CIDR allowlist, session timeouts in M9 |
| PIC-001 | in-progress | In-memory picture store with MGRS cell index (`io.swoc2.app.picture`); fed by connections in M3 |
| PIC-003 | in-progress | Override layer persisted, audited, applied on read; CAC UI in M7 |
| PIC-004 | in-progress | Aging stale/delete from instance settings, per-connection hook ready; connection overrides in M3/M8 |
| PIC-005 | in-progress | Wipe API with confirmation and audit; admin UI in M8 |
| PIC-008 | done | No fusion; one contact per source key (`SourceKey`) |
| PIC-009 | done | Identity = (connection, source system, track) - `SourceKey` |
| MAP-019 | in-progress | STALE state set by aging; grey rendering in M6 |
| GEN-011 | in-progress | Highest classification of displayed data in `/api/picture/summary`; banner UI in M5 |
| SDX-002 | done | TCP client/server, UDP unicast/multicast, MQTT 3.1.1/5 (one file each, ADR 0021) |
| SDX-005 | done | CONTACT/OWNUNIT -> picture (incl. relative positions, delete flag), HEARTBEAT -> connection health |
| CON-001 | in-progress | Manager API (CRUD, enable/disable, test before saving, field errors, audit); UI in M8 |
| CON-002 | in-progress | State, rates, errors, last error, reconnects, last heartbeat in `GET /api/connections`; UI in M8 |
| CON-003 | done | Backoff reconnect per connection, isolated runtimes (ADR 0021) |
| CON-005 | in-progress | Ingress: own sender dropped, dedup cache; egress rule ("never back to origin") with outbound in M3c |
| CON-006 | done | Connection type SPI (ADR 0021); built-in transports use it |
| DBG-001 | in-progress | Debug tap + `GET /api/debug/messages`; console UI in M7 |
| DBG-002 | in-progress | Enabled flag + roles in instance settings, enforced by the API |
| NFR-001 | done | Leon's laptop measurement 2026-10-03: 73 fps avg / 30.1 fps 1 % low at 100k + 10k updates/s (ADR 0018) |
| NFR-004 | in-progress | Ongoing |
| RNM-001 | in-progress | Transport fallback WS -> SSE -> long-poll, PR #7 (P1 requirement, built in P0 Spike B) |

## 0. Glossary

| Term | Meaning |
|---|---|
| **Contact** | Any object in the operational picture (track). It comes from a source or was created by a user. |
| **OWNUNIT** | Own asset reported via SEDAP-Express OWNUNIT (e.g. own drone). Can be tasked. |
| **Source** | Origin type of information: SEDAP-Express (with its source indicator, e.g. A, R), AIS, ADS-B, CoT, USER, ... |
| **Connection** | A configured input/output channel (e.g. a SEDAP-Express TCP client to host X). |
| **Live picture** | The volatile shared contact database. The admin can wipe it. |
| **Master data** | Persistent reference data (MMSI and ICAO mappings, images). Survives wipes and is mergeable across instances. |
| **Hook** | Primary focus on a contact (left click / tap). Shown as a **yellow circle**. |
| **Select** | Secondary focus (middle click or fallback). Shown as a **turquoise square**. |
| **CAC** | Contact Attribute Control. Panel showing all attributes of the hooked contact and allowing edits of editable ones. |
| **Plan** | Graphic plan or overlay (imported or drawn): points, lines, areas. |
| **Own position** | Configured position of the system/operator. Origin for range and bearing. |
| **Reference point** | User-placed point on the map. Range and bearing to the hooked contact are shown. |

## 1. General & operations (GEN)

| ID | Phase | Requirement |
|---|---|---|
| GEN-001 | P0 | One instance holds exactly one operational picture (one exercise). Multi-tenancy is not required, but the data model must not block it. |
| GEN-002 | P0 | The system runs fully offline / air-gapped. No CDN, no external fonts, no implicit internet calls. |
| GEN-003 | P0 | **HTTPS is the recommended operating mode.** Plain HTTP stays supported for local, dev, offline and emergency use without certificate errors. In the reference deployment, HTTPS is terminated by a reverse proxy (see GEN-014). |
| GEN-004 | P0 | Runs behind a reverse proxy, including on a sub-path (e.g. `https://host/swoc2/`). Forwarded headers are respected. |
| GEN-005 | P0 | The HTTP port, base path, public URL and all other settings are configurable via environment variables. |
| GEN-006 | P0 | Deployable as a Docker container (compose), as a plain JAR in an LXC (systemd), and later on Kubernetes (Helm). |
| GEN-007 | P0 | The UI is in English only. |
| GEN-008 | P1 | Light/dark theme, switchable per user. |
| GEN-009 | P0 | Robust error handling everywhere. Admin and user mistakes are caught with clear messages. Bad input never crashes a component. |
| GEN-010 | P0 | A diagnostic page reachable **without login** (`/diag`) shows browser capabilities (WebGL 1/2, WebSocket, SSE, long-poll round trip, secure context) and the resulting transport and render modes. Used to test locked-down service computers. |
| GEN-011 | P1 | A classification banner shows the highest classification of the data currently displayed (derived from SEDAP message classification). |
| GEN-012 | P1 | **Secure-context degradation:** features that need a secure context (Clipboard API, desktop notifications, service workers, ...) detect its absence and degrade gracefully. Examples: "copy coordinate" falls back to a selectable copy dialog; alarms are shown in-app only. `/diag` lists which features are limited in the current mode. |
| GEN-013 | P1 | **Optional built-in TLS:** SWOC2 can serve HTTPS directly (certificate and key from files, or an auto-generated self-signed certificate), so an LXC without a reverse proxy can run HTTPS with minimal configuration. |
| GEN-014 | P3 | **Reference deployment for service computers** (not priority 1, but the architecture must not prevent it): service computers cannot reach a host inside the service network. They access SWOC2 from the internet through a public reverse proxy (e.g. Caddy with a publicly trusted certificate) and through their corporate proxy (port 443 only, possibly TLS inspection, possibly no WebSockets). This path is a first-class, tested deployment. |
| GEN-015 | P1 | **Internet-facing hardening** (because of GEN-014): security headers (CSP, HSTS when on HTTPS, frame-ancestors, referrer policy), rate limiting on login, registration and invite redemption, session timeout, and an optional IP/CIDR allowlist for the admin dashboard and admin API. MFA is enforced via Keycloak policy (not in SWOC2). |

## 2. Authentication, users, roles (AUTH)

| ID | Phase | Requirement |
|---|---|---|
| AUTH-001 | P0 | Login via Keycloak (OIDC). Issuer URL, client ID and secret come from env vars. SWOC2 makes no assumption about where Keycloak runs. |
| AUTH-007 | P1 | **Recommended setup for hosted instances:** a shared Keycloak with **one realm per SWOC2 instance** (own frontend URL, own users and policies, service account with user-management rights limited to that realm). Documented step by step in `deploy/README.md`. |
| AUTH-008 | P1 | **Offline/air-gapped setup:** compose profile `bundled-keycloak` ships a Keycloak with a realm import (roles, SWOC2 client, invite service account). It uses its own database on the same PostgreSQL server. It is reachable under `{base}/auth` behind the same proxy, so it is a single origin. Admin bootstrap credentials come from env vars. |
| AUTH-009 | P3 | **Identity brokering (documentation only, no SWOC2 code):** guide for linking a per-instance realm or a bundled Keycloak to a central realm, so central accounts work when online while local emergency accounts keep working offline. |
| AUTH-002 | P0 | Roles: **Viewer** (read only), **Operator** (edit contacts, chat, plans, reference points, own contacts), **Commander** (Operator + send COMMANDs), **Admin** (everything + admin dashboard). Roles are client roles of the SWOC2 client in Keycloak, so other systems are unaffected. |
| AUTH-003 | P1 | Registration is only possible with an **invite token** generated by an admin (expiry, max uses, role to grant). Redeeming works for new users (account is created) and for existing Keycloak users (role is granted). Optionally, an admin must approve. |
| AUTH-004 | P0 | Multi-session: every user has an independent view (extent, layers, filters, symbol settings, panel layout, units, theme, replay position). This is persisted server-side per user. One user's actions on their view never affect other users. |
| AUTH-005 | P1 | Changes to the shared picture (contact edits, plans shared with all, commands) are visible to all and are audited. |
| AUTH-006 | P3 | Service accounts / API clients (client-credentials) for automation and AI agents, with the same RBAC. |

## 3. Map (MAP)

| ID | Phase | Requirement |
|---|---|---|
| MAP-001 | P1 | The core view is a 2D map. All other views are panels that can be shown, hidden, docked, undocked and minimised. |
| MAP-002 | P1 | Every data source / connection renders into its own layer. Layers can be toggled. The layer tree is grouped by source type → connection. |
| MAP-003 | P1 | Every symbol shows its source, at minimum in the CAC and tooltip, and optionally as a label (see MAP-008). |
| MAP-004 | P1 | The admin can add arbitrary **WMS and WMTS** layers through a guided wizard: enter URL → GetCapabilities parsed → choose layer, style, format, CRS → live preview → save. Errors are explained in plain language. |
| MAP-005 | P1 | Layers are either base layers (one active) or overlays (several, transparent, e.g. clouds or rain radar). Overlays are always drawn above base layers. Opacity is adjustable per user. |
| MAP-006 | P1 | An offline fallback basemap is bundled (low-detail world map), so the map is never empty. The admin can upload offline tile packages (MBTiles/PMTiles). |
| MAP-007 | P1 | Symbol settings per user: size, level of detail (amplifiers and modifiers shown), filled or frame only. |
| MAP-008 | P1 | Label settings per user. Selectable fields: timestamp, name, track number, source (SEDAP source indicator such as A/R, or AIS/ADS-B/...), coordinates, speed, course. |
| MAP-009 | P0 | Performance target: up to **100k contacts** in the picture with smooth pan and zoom (see NFR). Expected normal load: a few hundred SEDAP contacts at about 1 Hz, plus AIS. |
| MAP-010 | P1 | **Hook:** primary click or tap on a contact hooks it (yellow circle). **Select:** middle click selects (turquoise square). Fallbacks for devices without a middle button: Shift+click, a "select mode" toggle, and the context menu. |
| MAP-011 | P1 | Touch and gesture support (pan, pinch zoom, tap = hook, long-press = context menu), suitable for large touch monitors. |
| MAP-012 | P1 | Range and bearing from own position to a contact can be shown with one click (contact context menu and CAC). |
| MAP-013 | P1 | Distance line between hooked and selected contact (line with range/bearing label), shown on demand. A checkbox in the map settings ("auto distance line") draws it automatically whenever both exist. It is **not** automatic by default. |
| MAP-014 | P2 | **Grid aggregation** (optional per user): an MGRS-aligned grid is drawn and each cell shows the number of contacts **per identity** (friend/hostile/neutral/unknown, ...). The cell size follows zoom (100 km → 10 km → 1 km). Above a configurable zoom level, individual symbols are shown instead. |
| MAP-015 | P2 | Track trails (history tail) per layer, length configurable by the user. |
| MAP-016 | P1 | Coordinate display format per user: decimal degrees, deg-dec-min, deg-min-sec, MGRS. Units per user: distance (m/km/NM/ft), speed (m/s, km/h, kn), altitude (m/ft). Bearings are true. Magnetic bearings are optional later. |
| MAP-017 | P1 | **Coordinate input component** used everywhere: accepts and auto-detects any supported format (also pasted text), formats while typing, validates, allows picking from the map and shows the parsed result in other formats. Must be very ergonomic. |
| MAP-018 | P2 | **Reference points:** users can place points on the map, drag them, edit coordinates via the input component, and name them. A panel lists all reference points with range/bearing to the hooked contact. Reference points are personal by default and can be shared. |
| MAP-019 | P1 | Contacts not updated for time *y* turn grey (stale) and are removed after time *x* (see PIC-004). |
| MAP-020 | P4 | A 3D map plugin (Cesium) shows the same picture and layers. |
| MAP-021 | P1 | Map tool framework: tools (measure, range rings, ...) are registered via the SDK so new ones can be added without core changes. See TLS. |

## 4. Live picture & contacts (PIC)

| ID | Phase | Requirement |
|---|---|---|
| PIC-001 | P1 | Shared live contact store, identical for all users, server-authoritative. |
| PIC-002 | P1 | **Contact list panel:** searchable and filterable (text, source, identity, dimension, stale), sortable. Click hooks the contact and centres the map. |
| PIC-003 | P1 | **CAC panel:** shows all attributes of the hooked contact (including raw source fields). Editable fields (name, remarks, identity, classification/dimension, symbol code, ...) can be changed by Operators. Changes apply **for all users**, are stored as an **override layer** (they are not lost on the next source update) and are audited. Overrides can be reset. |
| PIC-004 | P1 | Aging per source/connection: stale after *y*, delete after *x*. A global default applies, which can be overridden per connection in the admin dashboard. OWNUNITs are included. User-created contacts are excluded. |
| PIC-005 | P1 | Admin can wipe the live picture (with confirmation and audit). This does not affect master data, plans, user-created contacts or history. |
| PIC-006 | P2 | **User-created contacts:** drawn on the map, attributes set in the CAC, draggable, never aged. They appear in their own list ("Own contacts"). A **Send** toggle/button in the CAC distributes them via SEDAP-Express CONTACT. Moving re-sends. Deleting sends the deletion according to the ICD. |
| PIC-007 | P2 | Media attached to a CONTACT (per ICD) is displayed in the CAC (image viewer; video via VID). |
| PIC-008 | P1 | No automatic fusion. The data model supports multiple sources per real-world object. External fusion (e.g. MESE sensor fusion) delivers fused tracks as a normal source. A simple optional correlation plugin may come later (P4). |
| PIC-009 | P1 | Contact identity in the system = (connection, source system ID, source track ID). It is stable across updates. |

## 5. Master data (MD)

| ID | Phase | Requirement |
|---|---|---|
| MD-001 | P2 | Persistent tables for vessels (key MMSI) and aircraft (key ICAO 24-bit address). They are separate from the live picture and never wiped with it. |
| MD-002 | P2 | **Auto-capture:** every MMSI or ICAO seen from any source is stored or updated with all known attributes (name, callsign, type, flag, dimensions, ...), with provenance and last-seen time. |
| MD-003 | P2 | **Enrichment:** when an MMSI/ICAO appears, its stored master data fills missing attributes of the contact automatically. |
| MD-004 | P2 | Operator edits to a contact with an MMSI/ICAO can be written back to master data (checkbox "save to master data", default on). |
| MD-005 | P3 | Images per vessel/aircraft are stored locally. Optional provider plugins query common external APIs when online (respecting their terms and attribution) and cache the results. |
| MD-006 | P3 | **Export / import / merge** of master data packages (including images) between instances, so a central master database can be built over exercises and used to seed new instances. Merging is field-level with provenance and timestamps. Conflicts are logged and resolvable. |
| MD-007 | P3 | Admin UI to browse, search, edit and delete master data entries. |

## 6. SEDAP-Express (SDX)

The ICD in `docs/icd/` is authoritative.

| ID | Phase | Requirement |
|---|---|---|
| SDX-001 | P0 | Parse and generate **all** message types of the ICD, with tolerant parsing (unknown or invalid fields are kept raw and produce a warning, not a failure). Round-trip tests cover every type. |
| SDX-002 | P1 | Transports: TCP client, TCP server, UDP unicast, UDP multicast, MQTT (broker, topic(s), QoS, auth configurable). |
| SDX-003 | P4 | Additional transports: REST and Protobuf (per the SEDAP-Express sub-projects). |
| SDX-004 | P1 | The sender ID is configured per instance by the admin. The message numbering follows the ICD. |
| SDX-005 | P1 | CONTACT and OWNUNIT are mapped to the live picture, and HEARTBEAT to connection health. |
| SDX-006 | P1 | TEXT is used as the station chat (see CHT). |
| SDX-007 | P2 | COMMAND: the **full COMMAND palette** of the ICD can be created (see TSK). |
| SDX-008 | P2 | ACKNOWLEDGE handling for outgoing messages that request it, and ACK generation for incoming messages that require it. |
| SDX-009 | P2 | STATUS is shown in the status board. Media URLs in STATUS are handed to VID. |
| SDX-010 | P2 | GRAPHIC: receive and display, and send plans (see PLN). |
| SDX-011 | P3 | METEO is displayed sensibly (wind barbs and values at position, togglable layer). |
| SDX-012 | P3 | EMISSION is displayed as its own togglable layer, visually subordinate (must never clutter the picture). It is off by default. |
| SDX-013 | P4 | Crypto methods of the ICD (incl. KEYEXCHANGE). From P1 on, the codec and connection model already have the hooks for it (per-connection crypto profile, key store). |
| SDX-014 | P4 | **Native SEDAP** and other formats can be consumed through the same adapter SPI (interface definition follows later). |

## 7. Connection manager (CON)

| ID | Phase | Requirement |
|---|---|---|
| CON-001 | P1 | The admin creates, edits, enables, disables and deletes connections. A guided form per connection type validates its input. Connections can be tested before saving. |
| CON-002 | P1 | Monitoring per connection: state, last message, message rates in/out, error count, last errors, reconnect attempts. Shown clearly in the connection manager. |
| CON-003 | P1 | Reconnect with backoff. One failing connection never affects others. |
| CON-004 | P3 | **Forwarding/routing rules:** from connection(s) → to connection(s), with a filter by message type and other criteria. |
| CON-005 | P1 | **Loop prevention (always on):** a message is never sent back to the connection it came from. Messages carrying our own sender ID are dropped on ingress. A dedup cache prevents multi-hop loops. |
| CON-006 | P1 | Connection types are pluggable (AIS, ADS-B, CoT, ... come as plugins, see PLG). |
| CON-007 | P1 | Commands addressed to an OWNUNIT are sent via the connection on which that OWNUNIT was last received (overridable). |

## 8. Tasking (TSK)

| ID | Phase | Requirement |
|---|---|---|
| TSK-001 | P2 | Right-click (or long-press) on an OWNUNIT → "Create command" opens the tasking panel in guided mode. |
| TSK-002 | P2 | Guided wizard: 1) command type → 2) dynamic parameters for that type (form generated from a per-type schema derived from the ICD) → 3) timing (start time etc.) and options → 4) review → send. |
| TSK-003 | P2 | Coordinate parameters can be picked by clicking on the map (e.g. Move To), and also via the coordinate input component. |
| TSK-004 | P2 | Area-based tasking: select or draw a plan → assign a task and asset(s). |
| TSK-005 | P2 | Tasks can be saved as **drafts** and assigned to assets later. There is a task list with filters (draft, sent, acknowledged, executing, done, rejected, no response). |
| TSK-006 | P2 | Per command: "expect acknowledgement" yes/no. Fire-and-forget is allowed. |
| TSK-007 | P2 | Lifecycle tracking: draft → sent → acknowledged → executing → done / rejected, via ACKNOWLEDGE and STATUS. A missing ACK leads to a **quiet** "no response" state with an optional single notification. Alarms never flood. |
| TSK-008 | P2 | Only Commanders and Admins can send commands. Every command is audited. |

## 9. Chat (CHT)

| ID | Phase | Requirement |
|---|---|---|
| CHT-001 | P1 | A chat icon on the main page opens the SEDAP chat panel. The panel can be docked (pinned at the side) or minimised again. |
| CHT-002 | P1 | Unread messages increment an iOS-style badge counter on the icon (per user). |
| CHT-003 | P1 | The station chat via SEDAP TEXT: all users see all messages. Sending as broadcast or to a specific recipient (sender ID). The SWOC2 user who sent a message is shown internally and audited. |
| CHT-004 | P4 | Matrix plugin: users log into a Matrix server and chat individually from within the dashboard. |

## 10. Graphic plans (PLN)

| ID | Phase | Requirement |
|---|---|---|
| PLN-001 | P2 | Users can import GeoJSON, KML/KMZ, GPX and NVG (NATO Vector Graphics). |
| PLN-002 | P4 | GOG import (NASA WorldWind format; definition to follow). |
| PLN-003 | P2 | Drawing tools: point, line, polygon, circle, rectangle; editing vertices; moving. |
| PLN-004 | P2 | Per-plan styling: colours, opacity, line width/style, fill, labels. |
| PLN-005 | P2 | The plan list panel lists all plans. All context-menu actions are also available there (show/hide, style, share, send, delete, zoom to, attach rules, use for tasking). |
| PLN-006 | P2 | Visibility per plan: private / shared with all users (right-click "Share") / additionally sent via SEDAP GRAPHIC (right-click "Send"). |
| PLN-007 | P4 | Multi-point tactical graphics (APP-6 control measures for mission planning) as a plugin. |

## 11. Alarms & geofencing (ALR)

| ID | Phase | Requirement |
|---|---|---|
| ALR-001 | P3 | Any area plan can be bound to rules: no entry, no exit, alarm for specific contact categories (dimension air/surface/ground/subsurface, identity hostile/suspect/..., source, custom filter). |
| ALR-002 | P3 | Notification centre with alarm list, acknowledge, mute, and history. Rate limiting and hysteresis prevent alarm floods. |
| ALR-003 | P3 | The rule engine is extensible via SDK (new rule types without core changes). |

## 12. Video & media (VID)

| ID | Phase | Requirement |
|---|---|---|
| VID-001 | P2 | Video dashboard listing streams from **multiple MediaMTX servers** configured in the admin dashboard. |
| VID-002 | P2 | One MediaMTX is designated as the **main media server**. Clients only receive video through it, proxied via SWOC2. Other servers are only tapped on demand (relayed into the main server). Clients never need direct reachability to sources. |
| VID-003 | P2 | Playback via WebRTC (WHEP), with HLS fallback over HTTPS for restricted networks. |
| VID-004 | P2 | The admin dashboard shows a clear warning when no main MediaMTX is configured. Video features are then hidden or marked unavailable. |
| VID-005 | P2 | Media URLs from STATUS/CONTACT (e.g. a drone's own stream server) are registered automatically as pull sources on the main MediaMTX via its API. The drone uploads **once**, regardless of the number of clients. |
| VID-006 | P2 | Contacts can be linked to streams, automatically (VID-005) or manually. From the contact (CAC, context menu), the video opens directly in the HMI (floating or docked player). |
| VID-007 | P2 | Images and other media sent with CONTACT are viewable in the HMI (see PIC-007). |

## 13. Tools (TLS)

| ID | Phase | Requirement |
|---|---|---|
| TLS-001 | P1 | Measure tool (range and bearing between arbitrary points). |
| TLS-002 | P3 | EBL/VRM, range rings around contacts or points, CPA/TCPA between two contacts, intercept course and time, sensor and weapon coverage circles. |
| TLS-003 | P1 | Tools are registered via the SDK so that new tools can be added without core changes. |

## 14. Status board (STB)

| ID | Phase | Requirement |
|---|---|---|
| STB-001 | P3 | The status board lists own units and assets from suitable sources (SEDAP OWNUNIT/STATUS, CoT via plugin): status, battery/fuel where available, current task, last update, link to video. |

## 15. History & replay (HIS)

| ID | Phase | Requirement |
|---|---|---|
| HIS-001 | P3 | Efficient storage of picture history (compressed time series with downsampling per source). Retention is configurable. Target: at least 30 minutes always, a full day where performance allows. |
| HIS-002 | P3 | Replay with a time slider **per user** (replaying never affects other users or the live picture). |
| HIS-003 | P3 | Export of recordings (time range) as a file, importable into other instances. |

## 16. Export (EXP)

| ID | Phase | Requirement |
|---|---|---|
| EXP-001 | P3 | Briefing export of the current view as PNG/PDF with legend, DTG and classification banner. |
| EXP-002 | P3 | Export contacts and plans as CSV, GeoJSON or KML. |
| EXP-003 | P3 | Users can save and load named views (extent, layers, filters, panel layout). |

## 17. Admin dashboard (ADM)

| ID | Phase | Requirement |
|---|---|---|
| ADM-001 | P1 | User management: list users with the SWOC2 role, change roles, disable, approve pending users, invite tokens (create, revoke, list). |
| ADM-002 | P1 | Connection manager (see CON). |
| ADM-003 | P1 | Instance settings: sender ID, own position (via the coordinate input component, or picked on the map), aging defaults, debug console availability. |
| ADM-004 | P1 | Map layer wizard (see MAP-004/006). |
| ADM-005 | P1 | Wipe the live picture (see PIC-005). |
| ADM-006 | P1 | Audit log viewer (filter by user, action, time). |
| ADM-007 | P2 | MediaMTX servers (add, test, designate main). |
| ADM-008 | P2 | Plugin management (enable/disable, version, health). |
| ADM-009 | P1 | System health: connections, DB, memory, client sessions, transport mix. |
| ADM-010 | P4 | LLM / AI agent configuration (API endpoints, keys, allowed tools). |
| ADM-011 | P1 | The admin dashboard grows over time. Admin pages are SDK extension points, so plugins can add their own admin pages. |

## 18. Debug console (DBG)

| ID | Phase | Requirement |
|---|---|---|
| DBG-001 | P1 | Debug panel showing incoming and outgoing messages (raw and parsed), with the connection, direction and time, plus errors and warnings. Filterable, pausable, copyable. |
| DBG-002 | P1 | The admin can enable or disable the debug console globally and per user/role. It is enabled by default in dev. |

## 19. Restricted network mode (RNM)

| ID | Phase | Requirement |
|---|---|---|
| RNM-001 | P1 | Map, chat and tasking work through corporate proxies with only port 443 and **no WebSockets**. Transport fallback is automatic (WS → SSE → long-poll) and can be forced manually in the user settings. |
| RNM-002 | P3 | Video works in restricted mode via HLS over HTTPS when possible. |
| RNM-003 | P3 | If WebGL is unavailable, a Canvas renderer is used automatically. In that mode, server-side aggregation (MAP-014) is used earlier, and individual symbols only appear at lower densities. |
| RNM-004 | P1 | This is the **same codebase and instance**, not a separate product. A dedicated "lite" client is only built if testing proves it necessary. |
| RNM-005 | P3 | The complete user flow (login via Keycloak, map, chat, tasking, HLS video) works through **public reverse proxy → internet → corporate proxy** (GEN-014). Every URL the browser needs (SWOC2, Keycloak, video) must be reachable on port 443 over HTTPS, ideally under the same domain. |

## 20. Plugins (PLG)

| ID | Phase | Requirement |
|---|---|---|
| PLG-001 | P0 | Frontend plugin SDK with extension points: routes/pages, dockable panels, map layers, context menu entries, toolbar items, user settings sections, admin pages, symbol providers, map tools, notification sources, coordinate formats. |
| PLG-002 | P0 | Backend plugin SPI: connection types (transports), message codecs/adapters, enrichment providers, rule types, scheduled jobs, REST endpoints under `/api/plugins/{id}/`. |
| PLG-003 | P0 | Isolation: plugin failures are contained (error boundaries, exception barriers, timeouts). Plugins can be disabled at runtime. |
| PLG-004 | P1 | Phase 1: plugins live in the monorepo and are included at build time. P4: plugins can be loaded at runtime (upload in the admin dashboard). |
| PLG-005 | P2 | **Symbol providers:** APP-6 / MIL-STD-2525 via milsymbol (default). Custom icon sets (admin-uploaded SVG/PNG plus a code mapping). Other symbol sets (e.g. German police symbols) as plugins. Interfaces to external systems use mapping tables between symbol sets. |
| PLG-006 | — | Planned plugins: video dashboard (P2), tasking (P2), chat panel (P1), AIS (P3), ADS-B (P3), CoT (P4), 3D Cesium (P4), Matrix chat (P4), tactical graphics (P4), police symbols (P4), master-data image providers (P3), correlation (P4), MCP / agent bridge (P4). |

## 21. API & agents (API)

| ID | Phase | Requirement |
|---|---|---|
| API-001 | P0 | REST API documented with OpenAPI (generated). It is the same API the UI uses. |
| API-002 | P1 | Realtime protocol is documented in `docs/realtime-protocol.md`. |
| API-003 | P4 | An MCP server plugin exposes selected API capabilities as tools for AI agents, with RBAC and audit. |

## 22. Non-functional (NFR)

| ID | Phase | Requirement |
|---|---|---|
| NFR-001 | P0 | With WebGL on a mid-range laptop: 100k contacts in the picture, pan and zoom at ≥ 30 fps, no visible stutter on updates. |
| NFR-002 | P1 | Latency from server ingest to client display < 500 ms (p95) at 500 contacts / 1 Hz with 20 concurrent sessions. |
| NFR-003 | P1 | Server handles ≥ 100k contacts in the live store with total updates ≥ 10k/s without falling behind (measured with external load tools). |
| NFR-004 | P0 | Code is commented for maintainability (see CLAUDE.md). |
| NFR-005 | P0 | Accessibility basics: keyboard navigation in panels and dialogs, sufficient contrast in both themes, scalable UI for touch monitors. |
