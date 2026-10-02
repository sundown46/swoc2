package io.swoc2.app.realtime;

/**
 * The session id is unknown, expired or belongs to another user. Deliberately one exception for
 * all three: a client must not be able to probe which session ids exist (§9).
 */
class UnknownSessionException extends RuntimeException {

    UnknownSessionException() {
        super("Unknown or expired realtime session");
    }
}
