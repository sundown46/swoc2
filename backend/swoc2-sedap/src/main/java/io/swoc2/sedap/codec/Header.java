package io.swoc2.sedap.codec;

import java.time.Instant;

/**
 * Common message header (ICD §5). Every field is optional on the wire.
 *
 * @param number 7-bit per-type sequence number (0-127), or {@code null}
 * @param time sender timestamp, or {@code null}
 * @param sender free textual sender id, or {@code null}
 * @param classification P, U, R, C, S or T, or {@code null}
 * @param acknowledgement {@code true} if the sender requests an ACKNOWLEDGE
 * @param mac message authentication code (hex), or {@code null}
 */
public record Header(
        Integer number, Instant time, String sender, Character classification, boolean acknowledgement, String mac) {

    public static final Header EMPTY = new Header(null, null, null, null, false, null);
}
