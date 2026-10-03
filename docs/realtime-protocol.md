# SWOC2 realtime protocol (v1)

Status: Spike B (ROADMAP P0 item 8). Implements ARCHITECTURE §6 and ADR 0005. The envelope,
sequence and resync rules below are binding for every transport; message *payloads* for the real
picture (viewport filtering, aggregation, layers) are defined in P1 on top of this.

## 1. Concepts

- **Realtime session:** server-side state for one browser tab: id, owner (the logged-in user),
  outbound sequence counter, a replay buffer of recent envelopes, and subscriptions. It is
  independent of the transport, so a client can switch from WebSocket to SSE or long-polling
  without losing messages.
- **Transport:** how envelopes travel. Exactly one transport is attached to a session at a time;
  attaching a new one detaches the old one.

| # | Transport | Downstream (server -> client) | Upstream (client -> server) |
|---|---|---|---|
| 1 | WebSocket | `GET {base}/rt/ws?session=&after=` (upgrade), text frames | same socket |
| 2 | SSE | `GET {base}/rt/sse?session=&after=` (`text/event-stream`) | `POST {base}/rt/send?session=` |
| 3 | Long-polling | `GET {base}/rt/poll?session=&after=` (held up to 25 s) | `POST {base}/rt/send?session=` |

All endpoints require the normal login session cookie (BFF, ADR 0004); a session can only be used
by the user who created it. `POST` requests carry the CSRF token (cookie `XSRF-TOKEN` ->
header `X-XSRF-TOKEN`). WebSocket handshakes are same-origin only.

## 2. Session lifecycle

1. `POST {base}/rt/session` -> `201 {"sessionId": "...", "heartbeatMs": 10000, "pollHoldMs": 25000}`.
2. The client attaches a transport with `after=0`. The server first sends `hello`, then (after the
   client subscribes) a `snapshot`.
3. A session with no transport attached expires after **60 s** (`SWOC2_RT_SESSION_TTL`). Using an
   expired or foreign session id gives `404` (HTTP) / close code `4404` (WS); the client then
   creates a new session.

## 3. Envelope

Every message in both directions is one JSON object:

```json
{ "v": 1, "seq": 42, "type": "delta", "payload": { } }
```

| Field | Server -> client | Client -> server |
|---|---|---|
| `v` | protocol version, always `1` | must be `1` |
| `seq` | per-session sequence number, starts at 1, +1 per envelope, never reused | omitted |
| `type` | see §4 | see §5 |
| `payload` | object, type-specific | object, type-specific |

Unknown `type`s are ignored by both sides (forward compatibility). Envelopes that fail validation
are dropped and logged; they never close the connection. Max upstream envelope size: 64 KiB.

## 4. Server -> client types

| type | payload | in replay buffer |
|---|---|---|
| `hello` | `{sessionId, heartbeatMs}` | yes |
| `snapshot` | `{topic, items: [...]}` - full state of a topic; replaces everything the client held for it | yes |
| `delta` | `{topic, upserts: [...], removes: [ids]}` - changes since the previous envelope | yes |
| `heartbeat` | `{}` | **no**; carries the current `seq` without incrementing it |
| `error` | `{code, message}` | yes |

Deltas are batched per session (default 2 Hz, ARCHITECTURE §6).

## 5. Client -> server types

| type | payload | effect |
|---|---|---|
| `subscribe` | `{topic}` | server sends a `snapshot` of the topic, then deltas |
| `unsubscribe` | `{topic}` | stops deltas for the topic |
| `resync` | `{topic}` | server sends a fresh `snapshot` (after a detected gap) |
| `ping` | `{}` | server answers with `heartbeat` (round-trip measurement) |

## 6. Ordering, gaps and resume

- The client tracks the last `seq` it processed. Every non-heartbeat envelope must have
  `seq == last + 1`.
- **Gap** (`seq > last + 1`): the client discards the envelope, sends `resync` for each subscribed
  topic and continues from the snapshot. **Duplicate/old** (`seq <= last`): dropped silently.
- **Resume:** when a transport (re)attaches with `after=N`, the server replays every buffered
  envelope with `seq > N`. If `N` is older than the buffer (default: last 1000 envelopes / 60 s),
  the server sends `error {code:"resync-required"}` and the client resyncs all topics.
- SSE uses `id: <seq>` on every event, so the browser's automatic reconnect sends
  `Last-Event-ID`, which the server treats like `after`.

## 7. Heartbeats and liveness

- The server sends `heartbeat` every `heartbeatMs` (10 s) on WS and SSE when idle.
- The client treats **3 x heartbeatMs without any envelope** as a dead transport. This also
  catches proxies that accept SSE but buffer it (ARCHITECTURE §6).
- Long-poll: the server holds `GET /rt/poll` up to `pollHoldMs` (25 s) and answers with
  `[]` if nothing happened; the response is always a JSON **array** of envelopes.

## 8. Transport negotiation (client)

1. Try WebSocket. If it does not open within 5 s, or fails before the first `hello`, fall back.
2. Try SSE. If no `hello`/heartbeat arrives within 5 s, or the stream is buffered (heartbeat timeout),
   fall back.
3. Long-polling (always works where plain HTTPS works).
4. While degraded, retry the better transport every 60 s in the background. Switching is
   seamless: the new transport attaches with `after=lastSeq`.
5. The user can force a transport (Settings -> Realtime); then no fallback/upgrade happens.

`/diag` (GEN-010) shows which transports work from the current network.

## 9. Limits and errors

- Per user: max 10 realtime sessions (oldest is closed with `error {code:"session-limit"}`).
- WS close codes: `4400` invalid request, `4403` not your session, `4404` unknown session,
  `4409` replaced by another transport.
- HTTP: `400` invalid parameters, `401` not logged in, `403` foreign session or CSRF, `404`
  unknown session, all as `application/problem+json`.

## 10. Demo topic (spike only)

`demo` publishes N synthetic moving contacts (`{id, lat, lon, course, speed}`) with 2 Hz deltas
for testing the transports. It will be replaced by the real picture topics in P1.
