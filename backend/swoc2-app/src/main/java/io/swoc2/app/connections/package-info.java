/**
 * Connections (CON-001..007, SDX-002, ARCHITECTURE §11.2/§11.3): configured links to external
 * systems, their runtime (state, reconnect with backoff, metrics), and the SEDAP-Express ingest
 * pipeline into the live picture. Connection types are pluggable via the SPI in
 * {@code io.swoc2.pluginapi.connection}; the built-in ones live in {@code transport}.
 */
package io.swoc2.app.connections;
