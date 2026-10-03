package io.swoc2.sedap.schema;

/**
 * How a parameter can be filled in by pointing at the map (TSK-003). Lets the tasking wizard
 * offer "pick on map" for exactly the right parameters without per-command UI code.
 */
public enum PickKind {
    /** Typed in only. */
    NONE,
    /** Latitude of a position; paired with the {@link #LONGITUDE} field of the same group. */
    LATITUDE,
    /** Longitude of a position; paired with the {@link #LATITUDE} field of the same group. */
    LONGITUDE,
    /** Altitude belonging to a picked position (filled from the contact/terrain if known). */
    ALTITUDE,
    /** A contact in the picture (its SEDAP contact ID is sent). */
    CONTACT,
    /** Any referencable object: contact, point, emission or graphic (COMMAND 54-56). */
    OBJECT
}
