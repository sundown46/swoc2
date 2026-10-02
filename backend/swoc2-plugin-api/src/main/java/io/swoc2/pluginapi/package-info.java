/**
 * Public SPI for backend plugins: connection types, message adapters, enrichment providers,
 * rule types, scheduled tasks and plugin REST endpoints (REQUIREMENTS PLG-002).
 *
 * <p>This package is semver-stable. Breaking changes require the {@code needs-review} PR label
 * (CLAUDE.md "Git workflow"). Core application code must never import plugin implementations;
 * plugins depend only on this module and {@code swoc2-domain}.
 */
package io.swoc2.pluginapi;
