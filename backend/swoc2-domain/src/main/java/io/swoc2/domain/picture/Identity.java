package io.swoc2.domain.picture;

import java.util.Locale;

/** Standard identity (APP-6 / MIL-STD-2525), as encoded in the 2nd character of a letter SIDC. */
public enum Identity {
    PENDING,
    UNKNOWN,
    ASSUMED_FRIEND,
    FRIEND,
    NEUTRAL,
    SUSPECT,
    HOSTILE,
    EXERCISE_PENDING,
    EXERCISE_UNKNOWN,
    EXERCISE_ASSUMED_FRIEND,
    EXERCISE_FRIEND,
    EXERCISE_NEUTRAL,
    JOKER,
    FAKER,
    NONE;

    /** From a 15-character letter SIDC; anything unreadable is {@link #UNKNOWN}. */
    public static Identity fromSidc(String sidc) {
        if (sidc == null || sidc.length() < 2) {
            return UNKNOWN;
        }
        return switch (Character.toUpperCase(sidc.charAt(1))) {
            case 'P' -> PENDING;
            case 'U' -> UNKNOWN;
            case 'A' -> ASSUMED_FRIEND;
            case 'F' -> FRIEND;
            case 'N' -> NEUTRAL;
            case 'S' -> SUSPECT;
            case 'H' -> HOSTILE;
            case 'G' -> EXERCISE_PENDING;
            case 'W' -> EXERCISE_UNKNOWN;
            case 'M' -> EXERCISE_ASSUMED_FRIEND;
            case 'D' -> EXERCISE_FRIEND;
            case 'L' -> EXERCISE_NEUTRAL;
            case 'J' -> JOKER;
            case 'K' -> FAKER;
            case 'O' -> NONE;
            default -> UNKNOWN;
        };
    }

    /** Lower-case wire/UI name, e.g. {@code assumed_friend}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
