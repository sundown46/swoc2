package io.swoc2.sedap.codec;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of decoding one line. The decoder never throws: a line it cannot use at all (unknown
 * message name, empty, too long) yields no message and at least one warning.
 *
 * @param raw the line as received (without line terminator)
 * @param message the decoded message, or {@code null} if the line was rejected
 * @param warnings everything that was tolerated or why the line was rejected
 * @param compressed the line was deflate+BASE64 compressed (ICD §3.3) and was inflated first
 */
public record DecodeResult(String raw, SedapMessage message, List<DecodeWarning> warnings, boolean compressed) {

    public DecodeResult {
        warnings = List.copyOf(warnings);
    }

    public Optional<SedapMessage> decoded() {
        return Optional.ofNullable(message);
    }

    public boolean rejected() {
        return message == null;
    }

    public boolean clean() {
        return message != null && warnings.isEmpty();
    }
}
