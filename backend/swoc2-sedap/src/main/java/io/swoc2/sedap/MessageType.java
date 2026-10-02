package io.swoc2.sedap;

import java.util.Locale;
import java.util.Optional;

/** The 15 SEDAP-Express message types (ICD §6.1-§6.15). The enum name is the wire name. */
public enum MessageType {
    OWNUNIT,
    CONTACT,
    POINT,
    EMISSION,
    METEO,
    TEXT,
    GRAPHIC,
    COMMAND,
    STATUS,
    ACKNOWLEDGE,
    RESEND,
    GENERIC,
    HEARTBEAT,
    TIMESYNC,
    KEYEXCHANGE;

    /**
     * Looks up a wire name. Case-insensitive (the ICD only shows upper case, but accepting
     * {@code contact} costs nothing - CLAUDE.md "most tolerant reasonable parser").
     */
    public static Optional<MessageType> fromWireName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
