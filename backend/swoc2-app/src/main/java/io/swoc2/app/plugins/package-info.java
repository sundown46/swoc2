/**
 * Plugin host (ARCHITECTURE §8.2, PLG-002/003): discovers backend plugins via ServiceLoader,
 * runs every call into them through {@link io.swoc2.app.plugins.PluginInvoker} (timeout,
 * exception barrier, failure counting, automatic disable), mounts their endpoints under
 * {@code /api/plugins/{id}/} and exposes plugin health. Core code never imports plugin classes;
 * plugins only see {@code swoc2-plugin-api}.
 */
package io.swoc2.app.plugins;
