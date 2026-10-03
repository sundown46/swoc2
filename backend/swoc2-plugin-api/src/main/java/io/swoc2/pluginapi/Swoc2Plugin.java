package io.swoc2.pluginapi;

import java.util.List;

/**
 * Entry point of a backend plugin (PLG-002, ARCHITECTURE §8.2). Discovered via
 * {@link java.util.ServiceLoader} ({@code META-INF/services/io.swoc2.pluginapi.Swoc2Plugin}).
 *
 * <p>Every call into a plugin - lifecycle, endpoints, scheduled tasks - goes through the core's
 * plugin invoker, which applies a timeout and an exception barrier and disables the plugin after
 * repeated failures (PLG-003). A plugin therefore never needs to defend the core against itself,
 * but it must not assume it is called again after it failed.
 *
 * <p><strong>Stability:</strong> SPI version {@value #SPI_VERSION}. Until P1 is done this SPI is
 * a skeleton and may still change (with the {@code needs-review} label); after that it is semver.
 */
public interface Swoc2Plugin {

    /** SPI version this core implements; plugins declare which one they were built against. */
    String SPI_VERSION = "0.1";

    /** Stable id, {@code [a-z][a-z0-9-]{1,40}}; used in URLs ({@code /api/plugins/{id}/...}). */
    String id();

    String name();

    String version();

    /** The {@link #SPI_VERSION} the plugin was built against. */
    default String spiVersion() {
        return SPI_VERSION;
    }

    /** Whether the plugin runs without an admin enabling it first. */
    default boolean enabledByDefault() {
        return true;
    }

    /** Called once when the plugin is enabled. Keep it short; it runs under a timeout. */
    default void start(PluginContext context) {}

    /** Called when the plugin is disabled or the application stops. */
    default void stop() {}

    /** REST endpoints, mounted under {@code /api/plugins/{id}/endpoints/}. */
    default List<PluginEndpoint> endpoints() {
        return List.of();
    }

    /** Periodic background work. */
    default List<ScheduledTask> scheduledTasks() {
        return List.of();
    }
}
