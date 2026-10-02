# Compose stacks

Planned (ARCHITECTURE §2, not part of the P0 repo-skeleton change that added this placeholder):

- `docker-compose.yml` - prod-like stack, with profiles `bundled-keycloak` (AUTH-008) and
  `public-proxy` (GEN-014, a Caddy example for the service-computer reference deployment).
- `docker-compose.dev.yml` - adds a dev Keycloak (same realm import as `bundled-keycloak`, with
  test users), MediaMTX, and the SEDAP test sender (ROADMAP P0 item 5).
