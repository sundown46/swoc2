/**
 * Connection type SPI (CON-006, ARCHITECTURE §8.2, §11.2): a pluggable way to exchange frames with
 * an external system. The core's own SEDAP-Express transports (TCP, UDP, MQTT) implement exactly
 * this SPI, like any plugin would (CLAUDE.md principle 3). The core owns lifecycle, reconnect with
 * backoff, metrics, decoding and routing; a connection type only moves frames.
 */
package io.swoc2.pluginapi.connection;
