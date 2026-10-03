/**
 * Diagnostics (GEN-010): the unauthenticated {@code /diag} page and the small probe endpoints it
 * uses to test which realtime transports (ARCHITECTURE §6) actually work from a given browser
 * and network - WebSocket, SSE and long-polling - without needing a login. Everything here is
 * reachable anonymously, so every endpoint is deliberately cheap, bounded in time and size, and
 * reveals nothing about the server's internals (ARCHITECTURE §17).
 */
package io.swoc2.app.diag;
