package io.swoc2.app.connections;

import java.util.Map;

/** A connection definition failed validation; carries field-level messages for the form (CON-001). */
class InvalidConnectionException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    InvalidConnectionException(Map<String, String> fieldErrors) {
        super("Invalid connection: " + fieldErrors);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
