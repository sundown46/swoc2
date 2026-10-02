# 0014 - HTTPS recommended, HTTP supported with graceful degradation; service computers via public reverse proxy

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 must work for local/offline/dev/emergency use without certificate errors (GEN-003), but is
also reachable from the internet through a public reverse proxy for locked-down service
computers that sit behind corporate proxies allowing only port 443, sometimes with TLS
inspection and no WebSockets (GEN-014). These are different deployments with different trust
and connectivity assumptions, not variations of the same setup.

## Decision

Plain HTTP stays a fully supported mode (GEN-003); features that need a secure context
(Clipboard API, desktop notifications, service workers) detect its absence and degrade
gracefully instead of breaking (GEN-012, `/diag` reports which features are limited). HTTPS is
the recommended mode, terminated by a reverse proxy in the reference deployment, with an
optional built-in TLS mode for LXC installs without a reverse proxy (GEN-013). For service
computers specifically, the reference deployment is: service computer -> corporate proxy (443
only) -> internet -> a **public** reverse proxy (e.g. Caddy) with a **publicly trusted**
certificate, serving SWOC2 and Keycloak under the same domain (ARCHITECTURE §2).

Rationale (ARCHITECTURE §16, D-013): locked-down browsers reject self-signed or private-CA
certificates outright, so the service-computer path specifically needs a publicly trusted
certificate; but requiring HTTPS everywhere would break the local/offline/dev use case GEN-003
exists for. These are genuinely different requirements, not one compromise setting.

## Consequences

- The realtime transport fallback (ADR 0005) and HLS video (ARCHITECTURE §13) are the *expected*
  path for the service-computer deployment, not a rare edge case - WebRTC/UDP will typically not
  get through that path at all.
- Internet-facing hardening (GEN-015: security headers, rate limiting, session timeouts, admin
  IP allowlist, ARCHITECTURE §17) is mandatory specifically because of this deployment, not
  optional hardening for the paranoid.
- Keycloak must be told its true public URL including any path prefix (e.g. `/auth`), via the
  realm's Frontend URL setting, or it generates wrong redirect/resource URLs - this is set at
  the realm level so other realms on a shared Keycloak are unaffected (ARCHITECTURE §2, ADR
  0015).
- Full validation of the service-computer path is explicitly deferred to P3 (`public-proxy`
  compose profile, RNM-005) - this ADR fixes the target architecture, not the delivery date.
