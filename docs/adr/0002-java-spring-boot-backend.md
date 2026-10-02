# 0002 - Java / Spring Boot backend

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 needs a backend that can speak SEDAP-Express natively, handle many concurrent blocking
I/O connections (TCP/UDP/MQTT) efficiently, integrate with Keycloak over OIDC, and be
maintainable by the project's own team (ARCHITECTURE §1, §3).

## Decision

Build the backend in Java (current LTS, see ADR 0001) with Spring Boot. Use the reference
SEDAP-Express library `io.github.uniity-team:sedapexpress` as the codec foundation
(`swoc2-sedap`, see ADR 0008) rather than writing a new implementation of the protocol from
the ICD alone.

Rationale (ARCHITECTURE §16, D-001):
- The reference SEDAP-Express library is Java.
- The team's maintainer expertise is in Java/Spring.
- Spring's OIDC support (resource server + OAuth2 client) and Java's virtual threads
  (JEP 444, stable since JDK 21) are mature enough for the connection-manager and BFF-auth
  designs (ADR 0004, ARCHITECTURE §3 "Concurrency").

## Consequences

- Backend plugins (`swoc2-plugin-api` consumers) are Java modules, not a separate runtime.
- Connection handling (one blocking thread per connection is acceptable) can use virtual
  threads instead of a reactive stack, keeping connector code straightforward to read and debug.
- Ties the project to the JVM ecosystem's release cadence; see ADR 0001 for the concrete
  version and its revisit triggers.
