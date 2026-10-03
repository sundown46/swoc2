package io.swoc2.app.picture;

import io.swoc2.domain.picture.Contact;

/**
 * One change of the live picture, as seen by listeners (realtime fan-out, history, alarms).
 *
 * @param contact the contact after the change (resolved through overrides); for {@code REMOVE} the
 *     last known state
 * @param cell MGRS 100 km cell after the change
 * @param previousCell cell before the change if the contact moved to another cell, else null
 */
public record PictureChange(Type type, Contact contact, String cell, String previousCell) {

    public enum Type {
        /** New contact, or any change of an existing one (position, attributes, state, override). */
        UPSERT,
        /** Removed by aging or wipe. */
        REMOVE
    }
}
