# 0021 - Connection SPI and runtime

Date: 2026-10-03
Status: Proposed (P1 M3a)

## Context

CON-006 requires pluggable connection types (AIS, ADS-B, CoT come as plugins); SDX-002 requires the
built-in SEDAP-Express transports; Leon asked for one source unit per connection type. CON-003:
one failing connection must never affect others.

## Decision

- **SPI** `io.swoc2.pluginapi.connection` (part of the public plugin API, `needs-review` on change):
  `ConnectionType` (id, name, frame format, supported directions, JSON Schema for the generated
  admin form, `validate`, `create`), `ConnectionInstance` (`start`/`stop`/`send` of frames),
  `ConnectionContext` (`received`, `state`, logger). The built-in transports implement exactly this
  SPI (CLAUDE.md principle 3).
- **Split of responsibilities:** a connection type only moves frames. The core owns lifecycle,
  reconnect with exponential backoff (1, 2, 5, 10, 30, 60 s), metrics, decoding (by frame format),
  loop prevention and mapping. Validation is Java code in `validate()`; the JSON Schema is only for
  the UI, so no JSON-Schema validator dependency is needed.
- **One file per transport** in `io.swoc2.app.connections.transport`; shared code is limited to
  `LineReader` (framing with a size limit) and `ConfigValues` (untrusted config checks).
- **Threads:** blocking sockets on virtual threads - simple, readable, and cheap enough for the
  expected tens of connections; no Netty.
- **Persistence:** `connection` table (Flyway V3), every change audited with secrets masked.
  Secret config fields are declared via `"writeOnly": true` in the schema, never returned by the
  API, kept on update when the client sends the mask, and encrypted at rest with AES-256-GCM using a
  key derived from `SWOC2_SECRET_KEY` (`SecretBox`, JDK only). Without that key, saving a secret is
  rejected with a field error instead of storing plaintext.
- **Transports (M3b):** UDP unicast and multicast (several messages per datagram, ICD §4; multicast
  group 228.2.19.80 by default), MQTT 3.1.1/5 with the HiveMQ client (P1 plan D2), publishing to
  `UNIITY-X/<sender>/<type>`, subscribing to a filter (default `UNIITY-X/#`). The library's own
  auto-reconnect is not used; the core's backoff applies to all transports alike.
- **Ingest** (ARCHITECTURE §4): decode -> debug tap -> drop own sender -> dedup
  `(sender, type, number, time)` for 60 s -> OWNUNIT route table -> CONTACT/OWNUNIT into the
  picture (relative X/Y resolved against the sender's OWNUNIT, else our own position, ICD §6.2),
  HEARTBEAT -> connection health, TEXT -> application event for the chat (M7).

## Consequences

- Plugin connection types for other protocols need a decoder for their frame format; that SPI
  (`MessageAdapter`) comes with the first such plugin.
- OWNUNITs have no ID field in the ICD; their identity is the sender ID (`SourceKey` track = sender).
