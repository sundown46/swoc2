package io.swoc2.domain.picture;

/**
 * Identity of a contact across updates (PIC-009): which connection delivered it, which system
 * inside that connection (e.g. SEDAP sender ID), and that system's own track ID. Two sources
 * reporting the same real object are two contacts (PIC-008, no automatic fusion).
 */
public record SourceKey(String connectionId, String sourceSystemId, String sourceTrackId) {

    private static final int MAX_LENGTH = 128;

    public SourceKey {
        connectionId = require(connectionId, "connectionId");
        sourceSystemId = sourceSystemId == null ? "" : check(sourceSystemId, "sourceSystemId");
        sourceTrackId = require(sourceTrackId, "sourceTrackId");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return check(value, name);
    }

    private static String check(String value, String name) {
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(name + " longer than " + MAX_LENGTH);
        }
        return value;
    }
}
