# Compose stacks

- `docker-compose.dev.yml` - dev Keycloak (realm `swoc2-dev`, imported from
  `../keycloak/realm-export.json`) plus a Caddy instance for the sub-path reverse-proxy test
  (ROADMAP P0 item 4; see `Caddyfile.dev` and `docs/TESTING.md` §1.2/§3.3). The app itself runs
  separately (`./backend/mvnw -pl swoc2-app spring-boot:run`, or the Docker image) so it's easy
  to iterate on without rebuilding this stack.

  ```bash
  docker compose -f deploy/compose/docker-compose.dev.yml up -d
  ```

  MediaMTX and the SEDAP test sender are added here when the features that need them land
  (VID-001 ff., P2; Spike C, ROADMAP P0 item 9).

Still planned (ARCHITECTURE §2, P3 unless noted):

- `docker-compose.yml` - prod-like stack, with profiles `bundled-keycloak` (AUTH-008, reusing
  the same realm export under `{base}/auth`) and `public-proxy` (GEN-014, a Caddy example with a
  publicly trusted certificate for the service-computer reference deployment - a different,
  harder problem than the plain-HTTP dev sub-path test above).
