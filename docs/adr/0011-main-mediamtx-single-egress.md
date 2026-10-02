# 0011 - Main MediaMTX as the single video egress, others relayed on demand

Date: 2026-10-02
Status: Accepted

## Context

SWOC2 must show video from multiple MediaMTX servers, including drone/media streams discovered
dynamically from SEDAP STATUS/CONTACT messages (VID-001, VID-005), without requiring every
client browser to be able to reach every stream source directly - clients may be on restricted
or air-gapped networks (GEN-002, RNM-002) and a drone's own stream server is not something the
SWOC2 operator necessarily controls network access to.

## Decision

Designate exactly one configured MediaMTX server as the **main** server. Clients only ever
receive video through the main server, proxied via SWOC2's own reverse proxy (WHEP signalling
and HLS). Streams from any other configured MediaMTX server, and drone/media URLs discovered
from STATUS/CONTACT, are made reachable by registering a **pull path on demand** on the main
server (`source` = remote URL, `sourceOnDemand: true`) - the main server pulls from the source
only when a client actually requests the stream (ARCHITECTURE §13).

Rationale (ARCHITECTURE §16, D-010): clients only ever need to reach SWOC2 itself, never any
drone or secondary media server directly; and a drone uploads its stream once regardless of how
many SWOC2 clients are watching it, instead of once per viewer.

## Consequences

- The admin dashboard must show a clear warning when no main MediaMTX is configured, and hide
  or mark video features unavailable in that case (VID-004) - there is no automatic "pick any
  server" fallback.
- Contact-to-stream links (VID-006) resolve through the main server regardless of which
  MediaMTX or source actually originates the stream.
- In restricted networks where the main MediaMTX's WebRTC/ICE/UDP port is not reachable from
  clients, playback falls back to HLS over HTTPS automatically (ARCHITECTURE §13, RNM-002).
