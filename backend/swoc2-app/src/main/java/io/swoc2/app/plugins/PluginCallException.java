package io.swoc2.app.plugins;

/** A plugin call failed, timed out, or the plugin is not enabled. Never carries plugin stack traces to clients. */
class PluginCallException extends RuntimeException {

    enum Reason {
        NOT_ENABLED,
        TIMEOUT,
        FAILED
    }

    private final Reason reason;

    PluginCallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
