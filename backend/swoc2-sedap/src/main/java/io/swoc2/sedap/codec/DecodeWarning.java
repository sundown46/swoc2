package io.swoc2.sedap.codec;

/**
 * Something the decoder tolerated (SDX-001). Shown in the debug console (DBG-001) and counted
 * in connection metrics; the message itself is still delivered.
 *
 * @param field ICD field name, or {@code null} for message-level problems
 * @param rawValue the offending raw text (truncated for logging), or {@code null}
 * @param message what is wrong, in plain English
 */
public record DecodeWarning(String field, String rawValue, String message) {

    private static final int MAX_RAW_CHARS = 80;

    public DecodeWarning {
        if (rawValue != null && rawValue.length() > MAX_RAW_CHARS) {
            rawValue = rawValue.substring(0, MAX_RAW_CHARS) + "...";
        }
    }

    static DecodeWarning message(String message) {
        return new DecodeWarning(null, null, message);
    }

    @Override
    public String toString() {
        return field == null ? message : field + ": " + message + (rawValue == null ? "" : " [" + rawValue + "]");
    }
}
