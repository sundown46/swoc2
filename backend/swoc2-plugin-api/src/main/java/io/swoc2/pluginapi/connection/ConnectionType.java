package io.swoc2.pluginapi.connection;

import java.util.Map;
import java.util.Set;

/**
 * A kind of connection, e.g. "SEDAP-Express TCP client". Stateless factory; registered once.
 *
 * <p>Calls into a connection type and its instances are guarded by the core (exceptions are caught
 * and counted, a failing connection never affects others, CON-003).
 */
public interface ConnectionType {

    /** Stable id, {@code [a-z][a-z0-9-]{1,40}}, stored with each connection. */
    String id();

    /** Human-readable name for the connection manager. */
    String name();

    /**
     * Frame format this type delivers, which selects the decoder, e.g. {@value #SEDAP_EXPRESS}.
     */
    String frameFormat();

    /** One SEDAP-Express message per frame (ICD §2), without the line terminator. */
    String SEDAP_EXPRESS = "sedap-express";

    /** Directions this type supports. */
    Set<Direction> directions();

    /**
     * JSON Schema (draft 2020-12) of the configuration, from which the admin UI generates the form
     * (CON-001, ARCHITECTURE §8.2). Fields with {@code "writeOnly": true} are secrets: the core
     * stores them encrypted and never returns them.
     */
    String configSchema();

    /**
     * Validates a configuration and returns field errors (field name to message); empty if valid.
     * Must check everything {@link #create} relies on - the form schema is only a UI aid.
     */
    Map<String, String> validate(Map<String, Object> config);

    /** Creates an instance for a valid configuration. Must not do I/O; {@code start()} does. */
    ConnectionInstance create(Map<String, Object> config, ConnectionContext context);
}
