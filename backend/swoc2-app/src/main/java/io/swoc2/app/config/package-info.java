/**
 * Runtime configuration the SPA reads at startup (ARCHITECTURE §12), and honouring forwarded
 * headers from a reverse proxy (GEN-004, ROADMAP P0 item 4) so redirects (notably the OAuth2
 * login callback) carry the right scheme/host/sub-path even when SWOC2 itself always answers
 * on {@code /}.
 */
package io.swoc2.app.config;
