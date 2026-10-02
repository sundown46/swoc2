# 0015 - Keycloak: one realm per SWOC2 instance on a shared Keycloak (hosted), bundled Keycloak profile (offline)

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 needs a Keycloak setup that works both for hosted instances (possibly many SWOC2
instances sharing infrastructure) and for fully offline/air-gapped instances (GEN-002), while
invite-based registration (AUTH-003) needs a service account that can create users and assign
roles - a capability that must not become a security exposure for *other* systems sharing the
same Keycloak.

## Decision

Two supported setups, both built on **one realm per SWOC2 instance** (ARCHITECTURE §2, §7):

1. **Hosted (recommended):** a shared Keycloak server, with each SWOC2 instance getting its own
   realm (own frontend URL, own users/policies, own service account scoped to that realm only).
2. **Offline/air-gapped:** the `bundled-keycloak` compose profile ships Keycloak in the stack
   with a realm import (roles, SWOC2 client, invite service account), using its own database on
   the same PostgreSQL server (ADR 0012), reachable under `{base}/auth` behind the same proxy so
   the whole instance is a single origin. This realm import is the same artefact the dev compose
   stack uses with test users (ROADMAP P0 item 5).

The invite-registration service account is granted `manage-users`/`view-users`/`query-users`
**only in that instance's own realm** - never realm-management rights on the shared Keycloak
itself.

Rationale (ARCHITECTURE §16, D-014; resolves `docs/OPEN_QUESTIONS.md` Q-003): realm isolation is
what makes a per-instance service account with user-management rights safe to grant at all -
without it, any instance's invite flow would need rights that could affect every other instance
or system on the shared Keycloak. Offline instances must be fully self-contained, which a shared
Keycloak cannot provide, hence the separate bundled profile.

## Consequences

- `SWOC2_OIDC_ISSUER_URI` must equal the public, browser-facing issuer URL for that instance's
  realm exactly (it must match the token `iss` claim) - see ADR 0014 on why path-based hosting
  needs the realm's own Frontend URL setting, not the server-wide hostname.
- A setup without a service account (no user-management rights granted at all) falls back to
  `SWOC2_REGISTRATION_MODE=existing-users-only`: the admin creates Keycloak accounts manually and
  invites only grant roles (ARCHITECTURE §7).
- Identity brokering to a central realm for setups that need both online central accounts and
  offline emergency accounts is documentation-only for now (AUTH-009, P3) - no SWOC2 code
  depends on it.
