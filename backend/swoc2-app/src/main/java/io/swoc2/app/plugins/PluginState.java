package io.swoc2.app.plugins;

/** Runtime state of a plugin as shown in plugin health (ADM-008). */
enum PluginState {
    /** Running; calls are accepted. */
    ENABLED,
    /** Switched off by configuration or an admin. */
    DISABLED,
    /** Switched off automatically after repeated failures (PLG-003). */
    FAILED
}
