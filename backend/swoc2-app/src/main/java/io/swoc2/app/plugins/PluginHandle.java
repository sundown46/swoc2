package io.swoc2.app.plugins;

import io.swoc2.pluginapi.Swoc2Plugin;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/** Core-side bookkeeping for one discovered plugin. Mutated only under its own lock. */
final class PluginHandle {

    private final Swoc2Plugin plugin;
    private PluginState state = PluginState.DISABLED;
    private long calls;
    private long failures;
    private int consecutiveFailures;
    private String lastError;
    private Instant lastErrorAt;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private List<io.swoc2.pluginapi.PluginEndpoint> endpoints = List.of();

    PluginHandle(Swoc2Plugin plugin) {
        this.plugin = plugin;
    }

    Swoc2Plugin plugin() {
        return plugin;
    }

    String id() {
        return plugin.id();
    }

    synchronized PluginState state() {
        return state;
    }

    synchronized void state(PluginState newState) {
        state = newState;
        if (newState == PluginState.ENABLED) {
            consecutiveFailures = 0;
        }
    }

    synchronized void recordSuccess() {
        calls++;
        consecutiveFailures = 0;
    }

    /** @return the consecutive failure count after this failure */
    synchronized int recordFailure(String error) {
        calls++;
        failures++;
        consecutiveFailures++;
        lastError = error;
        lastErrorAt = Instant.now();
        return consecutiveFailures;
    }

    /** Endpoints as reported by the plugin when it was enabled. */
    synchronized List<io.swoc2.pluginapi.PluginEndpoint> endpoints() {
        return endpoints;
    }

    synchronized void endpoints(List<io.swoc2.pluginapi.PluginEndpoint> newEndpoints) {
        endpoints = List.copyOf(newEndpoints);
    }

    synchronized List<ScheduledFuture<?>> scheduled() {
        return scheduled;
    }

    synchronized PluginHealth health() {
        return new PluginHealth(
                plugin.id(),
                plugin.name(),
                plugin.version(),
                plugin.spiVersion(),
                state,
                calls,
                failures,
                consecutiveFailures,
                lastError,
                lastErrorAt);
    }

    /** Plugin health entry (ADM-008), as returned by {@code GET /api/plugins}. */
    record PluginHealth(
            String id,
            String name,
            String version,
            String spiVersion,
            PluginState state,
            long calls,
            long failures,
            int consecutiveFailures,
            String lastError,
            Instant lastErrorAt) {}
}
