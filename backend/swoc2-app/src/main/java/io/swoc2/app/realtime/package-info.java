/**
 * Realtime module (ARCHITECTURE §6, ADR 0005, {@code docs/realtime-protocol.md}): transport-
 * independent realtime sessions with per-session sequence numbers, a replay buffer, snapshot +
 * delta + resync, and three interchangeable transports - WebSocket, SSE + POST, long-polling +
 * POST - so SWOC2 keeps working behind proxies that block WebSockets (RNM-001).
 *
 * <p>Spike B (ROADMAP P0 item 8): the only topic is the synthetic {@code demo} topic; real picture
 * topics are added in P1.
 */
package io.swoc2.app.realtime;
