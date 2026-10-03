# SWOC2 — Open questions

Claude Code adds questions here instead of guessing. Leon answers them inline. Answered questions
move to the "Resolved" section, with a pointer to the place where the decision was recorded.

## Open

| # | Topic | Question | Blocking for | Interim assumption |
|---|---|---|---|---|
| Q-002 | GOG | Format definition (NASA WorldWind GOG) still to be provided. | PLN-002 (P4) | — |
| Q-004 | Service computers | Is WebGL available on the target service computers? To be tested with `/diag` once P0 is deployed. | RNM-003 tuning | Canvas fallback with early aggregation |
| Q-005 | Master data APIs | Which external providers for vessel and aircraft data/images are acceptable (licence/ToS, attribution)? | MD-005 (P3) | Provider plugins, none enabled by default |
| Q-011 | SEDAP-Express reference library vs. ICD | The reference library v1.4.8 cannot parse ICD-conformant GRAPHIC messages (it only accepts one-digit GraphicTypes, the ICD writes `08`/`0A`), and expects camera mode `DL/IR/LI` instead of the ICD's `DayLight/InfraRed/LightIntensifier`. Partners using the library would reject what SWOC2 sends. Report upstream (UNIITY-Team/SEDAP-Express)? Until fixed, should SWOC2 send one-digit GraphicTypes for 0-9 and `DL/IR/LI` for compatibility, or stick to the ICD? Details: `docs/icd/NOTES.md`, ADR 0017. | SDX-010 / TSK (P2) | SWOC2 sends the ICD spelling (ICD is authoritative per CLAUDE.md) and accepts both on input |
| Q-006 | Police symbols | Which regulation/symbol set, and the mapping to SIDC for external interfaces? | P4 | Symbol-provider abstraction only |
| Q-007 | Native SEDAP | Interface definition for the native SEDAP adapter. | SDX-014 (P4) | Adapter SPI only |

## Deferred

| # | Topic | Question | Note |
|---|---|---|---|
| Q-009 | Corporate URL filter | Is the public SWOC2 domain blocked or uncategorised by the corporate proxy's URL filter? It may need whitelisting. | Cannot be tested now. Service computers are not priority 1 (see RNM, P3). Test with `/diag` once a public instance exists. |

## Resolved

| # | Topic | Decision | Recorded in |
|---|---|---|---|
| Q-003 | Keycloak service account | One realm per SWOC2 instance. The service account gets user-management rights only in that realm. A bundled Keycloak is used for offline setups. The `existing-users-only` mode remains as a fallback. | ARCHITECTURE §2, §7, D-014 |
| Q-001 | SEDAP ICD | The ICD is in `docs/icd/SEDAP-Express-ICD-for-AI-v1.4.8.md` (revision 1.4.8). | `docs/icd/` |
| Q-010 | SEDAP-Express Maven coordinates | `io.github.uniity-team:sedapexpress` **is** on Maven Central (`repo1.maven.org/maven2/io/github/uniity-team/sedapexpress/`, versions 1.3.0-1.4.8, latest 1.4.8 published 2026-09-09; also attached to the GitHub release `release-v1.4.8` of `UNIITY-Team/SEDAP-Express`). The Maven Central *search API* returned zero hits on 2026-10-02, which was a search-index problem, not a missing artefact. Leon approved adding the dependency including its transitive dependencies (2026-10-02). | ADR 0001, Spike C PR |
| Q-008 | Keycloak reachability | Leon publishes Keycloak under the SWOC2 domain as a reverse-proxy location (e.g. `/auth`). | ARCHITECTURE §2 |
