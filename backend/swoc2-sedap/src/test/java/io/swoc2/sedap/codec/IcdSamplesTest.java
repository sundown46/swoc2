package io.swoc2.sedap.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every sample of the ICD (SDX-001 "round-trip tests cover every type"): decodes with exactly the
 * expected warnings, and re-encodes to the identical line (modulo trailing semicolons, ICD §2).
 * The expectations live in {@code icd-samples.txt}; samples that are malformed in the ICD itself
 * are explained in {@code docs/icd/NOTES.md}.
 */
class IcdSamplesTest {

    record Sample(String expectation, String line) {
        @Override
        public String toString() {
            return line;
        }
    }

    static Stream<Sample> samples() throws IOException {
        try (InputStream in = Objects.requireNonNull(IcdSamplesTest.class.getResourceAsStream("/icd-samples.txt"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .filter(l -> !l.isBlank() && !l.startsWith("#"))
                            .map(l -> new Sample(l.substring(0, l.indexOf('|')), l.substring(l.indexOf('|') + 1)))
                            .toList()
                            .stream();
        }
    }

    private final SedapDecoder decoder = new SedapDecoder();

    @ParameterizedTest
    @MethodSource("samples")
    void decodesWithExactlyTheExpectedWarnings(Sample sample) {
        DecodeResult result = decoder.decode(sample.line());

        assertThat(result.rejected()).as("rejected: %s", result.warnings()).isFalse();
        Set<String> actual = result.warnings().stream()
                .map(w -> w.field() == null ? "-" : w.field())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> expected = sample.expectation().equals("clean")
                ? Set.of()
                : new TreeSet<>(Arrays.asList(
                        sample.expectation().substring("warn:".length()).split(",")));
        assertThat(actual).as("warnings were %s", result.warnings()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("samples")
    void reEncodesToTheIdenticalLine(Sample sample) {
        SedapMessage message = decoder.decode(sample.line()).message();

        assertThat(SedapEncoder.encode(message))
                .isEqualTo(stripTrailingSemicolons(sample.line().strip()));
    }

    static String stripTrailingSemicolons(String line) {
        String s = line;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static List<Sample> all() throws IOException {
        return samples().toList();
    }
}
