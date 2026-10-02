# 0004 - Backend-for-frontend auth with a session cookie

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 must support login via an external Keycloak (AUTH-001) while also working over plain
HTTP for local/offline/emergency use (GEN-003) and over WebSocket, SSE and HTTPS long-polling
transports interchangeably (ARCHITECTURE §6), through corporate proxies that may inspect or
restrict traffic (GEN-014/RNM-001). Plugins also load additional frontend code into the same
page (PLG-001), so a token readable by that code is a meaningfully larger attack surface than
one that is not.

## Decision

Use the backend-for-frontend (BFF) pattern: Spring Security acts as a confidential OIDC client
(authorization code flow + PKCE, server-side) against the external Keycloak. The browser only
ever holds an `HttpOnly`, `SameSite=Lax` session cookie (`Secure` auto-detected from the public
URL scheme). There are no OIDC tokens in the browser at all (ARCHITECTURE §7).

Rationale (ARCHITECTURE §16, D-003):
- **Transport independence:** a cookie travels automatically with WebSocket, SSE and
  long-polling requests. Token-based auth would need the token in the URL for `EventSource`
  (which cannot set headers), leaking it into proxy logs, plus refresh logic over long-lived
  connections.
- **Security:** an `HttpOnly` cookie cannot be read by XSS, which matters once plugin code runs
  on the same page.
- **Plain HTTP still works** (GEN-003) because nothing depends on Web Crypto, which browser
  OIDC libraries need and which only exists in secure contexts.

## Consequences

- CSRF protection is required on all state-changing requests (ARCHITECTURE §7).
- `SWOC2_OIDC_ISSUER_URI` must equal Keycloak's browser-facing URL and match the token `iss`
  claim exactly; `SWOC2_OIDC_BACKCHANNEL_URI` exists for the common Docker case where the
  backend reaches Keycloak at a different internal address.
- API clients that are not browsers (service accounts, future AI agents, AUTH-006) need a
  separate bearer-token resource-server mode layered on top later; it does not replace the BFF
  cookie for the browser UI.
- **Spring Boot's auto-configured `ClientRegistrationRepository` resolves the OIDC registration
  eagerly, during application context startup** - including the live discovery HTTP call to
  Keycloak's issuer. Left as-is, this means SWOC2 refuses to boot at all (not even
  `/config.json` or `/actuator/health`) if Keycloak is unreachable or still starting when SWOC2
  does, or if the OIDC env vars are simply not set yet - confirmed by a real incident: a plain
  `docker run` of the image with no Keycloak configured crashed the JVM outright. That violates
  CLAUDE.md principle #1 ("one ... failing [dependency] must never crash the process"), so
  `LazyClientRegistrationConfig` replaces it with one that defers the discovery call to the
  first real login attempt instead (memoized afterwards). Keep this in mind for any other
  external-service integration added later (master-data image providers, MediaMTX, SEDAP
  connections already get this right via the connection manager's own backoff/isolation,
  ARCHITECTURE §11.2) - eager resolution at context-startup is an easy trap with Spring Boot
  auto-configuration in general, not specific to OAuth2 clients.
