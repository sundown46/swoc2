# SWOC2 — Open questions

Claude Code adds questions here instead of guessing. Leon answers them inline. Answered questions
move to the "Resolved" section, with a pointer to the place where the decision was recorded.

## Open

| # | Topic | Question | Blocking for | Interim assumption |
|---|---|---|---|---|
| Q-001 | SEDAP ICD | Leon adds the ICD as Markdown to `docs/icd/`. | P0 Spike C | — |
| Q-002 | GOG | Format definition (NASA WorldWind GOG) still to be provided. | PLN-002 (P4) | — |
| Q-004 | Service computers | Is WebGL available on the target service computers? To be tested with `/diag` once P0 is deployed. | RNM-003 tuning | Canvas fallback with early aggregation |
| Q-005 | Master data APIs | Which external providers for vessel and aircraft data/images are acceptable (licence/ToS, attribution)? | MD-005 (P3) | Provider plugins, none enabled by default |
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
| Q-008 | Keycloak reachability | Leon publishes Keycloak under the SWOC2 domain as a reverse-proxy location (e.g. `/auth`). | ARCHITECTURE §2 |
