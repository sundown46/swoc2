package io.swoc2.pluginapi;

import org.slf4j.Logger;

/**
 * What the core offers a plugin (ARCHITECTURE §8.2). Deliberately small in the skeleton; grows
 * with P1 (picture access, connections, settings store) without breaking existing plugins.
 */
public interface PluginContext {

    String pluginId();

    /** Logger named after the plugin, so its output is attributable in logs and the debug console. */
    Logger logger();
}
