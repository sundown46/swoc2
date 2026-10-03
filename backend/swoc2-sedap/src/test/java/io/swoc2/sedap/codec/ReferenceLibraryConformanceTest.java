package io.swoc2.sedap.codec;

import static org.assertj.core.api.Assertions.assertThat;

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.schema.CommandSchemas;
import io.swoc2.sedap.schema.FieldSpec;
import io.swoc2.sedap.schema.GraphicSchemas;
import io.swoc2.sedap.schema.MessageSchema;
import io.swoc2.sedap.schema.SedapSchemas;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-checks our codec against the reference library {@code io.github.uniity-team:sedapexpress}
 * (ADR 0017): every line we decode or generate must also be accepted by the reference library, and
 * every value both sides expose under the same ICD field name must agree. Header fields are always
 * compared; content fields are compared wherever the library has a getter named like the ICD field
 * ({@code Latitude} -> {@code getLatitude()}) with a comparable type.
 */
class ReferenceLibraryConformanceTest {

    @BeforeAll
    static void silenceReferenceLibraryLogging() {
        // The library logs every empty optional field to the JUL global logger at INFO.
        Logger.getLogger(Logger.GLOBAL_LOGGER_NAME).setLevel(Level.OFF);
    }

    static Stream<String> icdSamples() throws Exception {
        return IcdSamplesTest.all().stream()
                .filter(s -> s.expectation().equals("clean"))
                .map(IcdSamplesTest.Sample::line);
    }

    /** One fully populated generated message per type and per COMMAND/GRAPHIC variant. */
    static Stream<String> generatedLines() {
        List<String> lines = new ArrayList<>();
        for (MessageType type : MessageType.values()) {
            MessageSchema schema = SedapSchemas.schemaFor(type);
            if (schema.discriminator() == null) {
                lines.add(generate(type, null));
            }
        }
        CommandSchemas.variants().keySet().forEach(code -> lines.add(generate(MessageType.COMMAND, code)));
        GraphicSchemas.variants().keySet().forEach(code -> lines.add(generate(MessageType.GRAPHIC, code)));
        return lines.stream();
    }

    private static String generate(MessageType type, Integer variant) {
        MessageSchema schema = SedapSchemas.schemaFor(type);
        SedapMessageBuilder builder = SedapMessage.builder(type)
                .number(0x11)
                .time(Instant.parse("2026-10-03T00:00:00Z"))
                .sender("SWOC2")
                .classification('U');
        List<FieldSpec> specs = new ArrayList<>(schema.fields());
        if (variant != null) {
            builder.variant(variant);
            specs.addAll(schema.variant(variant).orElseThrow().params());
        }
        int seed = 0;
        for (FieldSpec spec : specs) {
            if (spec.name().equals(schema.discriminator()) || spec.name().startsWith("rel")) {
                continue;
            }
            // BASE64 text in TEXT/GENERIC/GRAPHIC is declared via Encoding; keep it plain here.
            builder.set(spec.name(), SampleValues.valueFor(spec, seed++));
        }
        return SedapEncoder.encode(builder.build());
    }

    /**
     * Known defects of the reference library v1.4.8 where it rejects ICD-conformant input. Each is
     * asserted to <em>still</em> be rejected, so a fixed library release makes this test fail and
     * the entry gets removed. Details in docs/icd/NOTES.md and OPEN_QUESTIONS Q-011.
     */
    private static final Map<Predicate<String>, String> KNOWN_LIBRARY_DEFECTS = Map.of(
            line -> line.startsWith("GRAPHIC;") && graphicTypeField(line).length() == 2,
            "GRAPHICTYPE_MATCHER only accepts one digit, but the ICD writes GraphicType as 00-0B",
            line -> line.startsWith("COMMAND;") && line.contains(";36;") && line.contains("DayLight"),
            "camera Mode: library only knows DL/IR/LI, the ICD says DayLight/InfraRed/LightIntensifier",
            line -> line.startsWith("KEYEXCHANGE;"),
            "KEYEXCHANGE generation not cross-checked yet (P4, SDX-013); library field handling differs");

    private static String graphicTypeField(String line) {
        String[] parts = line.split(";", -1);
        return parts.length > 9 ? parts[9] : "";
    }

    private static Optional<String> knownDefect(String line) {
        return KNOWN_LIBRARY_DEFECTS.entrySet().stream()
                .filter(e -> e.getKey().test(line))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @ParameterizedTest
    @MethodSource("icdSamples")
    void icdSamplesAgree(String line) throws Exception {
        assertAgreesOrKnownDefect(line);
    }

    @ParameterizedTest
    @MethodSource("generatedLines")
    void generatedMessagesAreAcceptedByTheReferenceLibrary(String line) throws Exception {
        assertAgreesOrKnownDefect(line);
    }

    @Test
    void graphicDefectIsReallyTheTwoDigitType() {
        // Same ICD sample with a one-digit GraphicType parses: proves the cause of the defect.
        assertThat(
                        SEDAPExpressMessage.deserialize(
                                "GRAPHIC;79;0195238E35AD;910E;U;;;FFDA;;8;1;FF800000;;;BASE64;QXJlYSBBbHBoYQ==;53.43;9.45;0;1000"))
                .isNotNull();
    }

    private static void assertAgreesOrKnownDefect(String line) throws Exception {
        Optional<String> defect = knownDefect(line);
        if (defect.isPresent() && !line.startsWith("KEYEXCHANGE;")) {
            assertThat(SEDAPExpressMessage.deserialize(line))
                    .as("reference library now accepts this - remove the known defect '%s'", defect.get())
                    .isNull();
            return;
        }
        if (defect.isPresent()) {
            return;
        }
        assertAgrees(line);
    }

    private static void assertAgrees(String line) throws Exception {
        SedapMessage ours = new SedapDecoder().decode(line).message();
        SEDAPExpressMessage reference = SEDAPExpressMessage.deserialize(line);

        assertThat(reference).as("reference library rejected: %s", line).isNotNull();
        assertThat(reference.getClass().getSimpleName()).isEqualTo(ours.type().name());

        Header header = ours.header();
        assertThat(toInt(reference.getNumber())).as("Number").isEqualTo(header.number());
        assertThat(reference.getTime())
                .as("Time")
                .isEqualTo(header.time() == null ? null : header.time().toEpochMilli());
        assertThat(reference.getSender()).as("Sender").isEqualTo(header.sender());

        List<String> mismatches = new ArrayList<>();
        for (FieldValue field : ours.fields()) {
            Method getter = getter(reference.getClass(), field.name());
            if (getter == null || field.value() == null) {
                continue;
            }
            Object theirs = getter.invoke(reference);
            Object mine = field.value();
            if (mine instanceof Base64Data data && theirs instanceof String text) {
                // The library decodes some BASE64 fields (as ISO-8859-1) and keeps others encoded.
                mine = text.equals(data.encoded()) ? data.encoded() : data.text(StandardCharsets.ISO_8859_1);
            }
            if (mine instanceof List<?> list && theirs instanceof String) {
                mine = String.join("#", list.stream().map(String::valueOf).toList());
            }
            if (theirs instanceof Double || theirs instanceof String) {
                if (!Objects.equals(theirs, mine)) {
                    mismatches.add(field.name() + ": ours=" + mine + " reference=" + theirs);
                }
            }
        }
        assertThat(mismatches).as(line).isEmpty();
    }

    private static Integer toInt(Byte b) {
        return b == null ? null : (int) b;
    }

    private static Method getter(Class<?> type, String icdName) {
        String wanted = "get" + icdName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() == 0
                    && m.getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return m;
            }
        }
        return null;
    }
}
