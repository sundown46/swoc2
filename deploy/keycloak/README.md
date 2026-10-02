# Keycloak realm export

`realm-export.json` (realm `swoc2-dev`): the four AUTH-002 roles as client roles of the `swoc2`
client, that client itself (confidential, authorization-code + PKCE, redirect URIs for both the
plain dev setup and the Caddy sub-path test), the `swoc2-invite-service` service-account client
with `manage-users`/`view-users`/`query-users` on `realm-management` only (AUTH-003's future
invite flow, not wired into any endpoint yet), and one test user per role
(`docs/TESTING.md` §3.4 has the credentials). Used by `docker-compose.dev.yml`.

Two things worth knowing if you edit this file:

- Keycloak's `roles` client scope only puts `resource_access.<clientId>.roles` on the **access**
  token by default, not the ID token or userinfo (verified by decoding a real token during
  testing) - `SecurityConfig` reads it from there. Don't "fix" this by trying to make the claim
  appear on the ID token instead; the backend already handles the default behaviour correctly.
- Keycloak's declarative user profile fires a "verify profile" required action on first login
  if `firstName`/`lastName` are missing - every test user sets them (and `requiredActions: []`)
  so the automated login-flow test isn't interrupted by it.

Reused later, unchanged, by the `bundled-keycloak` compose profile (AUTH-008, P1) - that's why
it already imports cleanly standalone instead of assuming it's reachable under `{base}/auth`.
