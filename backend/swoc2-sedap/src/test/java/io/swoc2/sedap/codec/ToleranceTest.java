package io.swoc2.sedap.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.swoc2.sedap.MessageType;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Hostile and sloppy input (CLAUDE.md principle 1, SDX-001): nothing throws, invalid fields are
 * kept raw with a warning, and the rest of the message is still usable.
 */
class ToleranceTest {

    private final SedapDecoder decoder = new SedapDecoder();

    @Test
    void invalidLatitudeIsDroppedButMessageSurvives() {
        DecodeResult r = decoder.decode("CONTACT;01;;X;U;;;7;FALSE;95.5;8.11;;;;;;;;;;;;;Name");

        assertThat(r.rejected()).isFalse();
        assertThat(r.message().number("Latitude")).isEmpty();
        assertThat(r.message().field("Latitude").orElseThrow().raw()).isEqualTo("95.5");
        assertThat(r.message().text("Name")).contains("Name");
        assertThat(r.warnings()).extracting(DecodeWarning::field).contains("Latitude");
    }

    @Test
    void garbageInNumericFieldsIsKeptRaw() {
        DecodeResult r = decoder.decode("OWNUNIT;ZZ;nothex;S1;Q;MAYBE;mac!;abc;8.11;1e999;NaN;;;;;;");

        assertThat(r.rejected()).isFalse();
        assertThat(r.warnings())
                .extracting(DecodeWarning::field)
                .contains(
                        "Number",
                        "Time",
                        "Classification",
                        "Acknowledgement",
                        "MAC",
                        "Latitude",
                        "Altitude",
                        "SpeedOverGround");
        assertThat(r.message().number("Longitude")).contains(8.11);
        assertThat(SedapEncoder.encode(r.message())).isEqualTo("OWNUNIT;ZZ;nothex;S1;Q;MAYBE;mac!;abc;8.11;1e999;NaN");
    }

    @Test
    void decimalCommaIsTolerated() {
        DecodeResult r = decoder.decode("OWNUNIT;;;;;;;53,32;8.11");

        assertThat(r.message().number("Latitude")).contains(53.32);
        assertThat(r.warnings()).hasSize(1);
    }

    @Test
    void unknownMessageNameIsRejectedNotThrown() {
        DecodeResult r = decoder.decode("FOOBAR;1;2;3");

        assertThat(r.rejected()).isTrue();
        assertThat(r.warnings().getFirst().message()).contains("unknown message name");
    }

    @Test
    void lowerCaseNameIsAcceptedWithWarning() {
        DecodeResult r = decoder.decode("heartbeat;01");

        assertThat(r.message().type()).isEqualTo(MessageType.HEARTBEAT);
        assertThat(r.warnings()).hasSize(1);
    }

    @Test
    void extraTrailingFieldsAreKeptForForwarding() {
        String line = "HEARTBEAT;42;0195238E25AD;89AD;U;;;ORKA;future1;future2";

        DecodeResult r = decoder.decode(line);

        assertThat(r.message().extraFields()).containsExactly("future1", "future2");
        assertThat(SedapEncoder.encode(r.message())).isEqualTo(line);
        assertThat(r.warnings()).hasSize(1);
    }

    @Test
    void unknownCommandTypeKeepsParametersRaw() {
        String line = "COMMAND;01;;;;;;ORKA;0001;00;;7E;p1;p2";

        DecodeResult r = decoder.decode(line);

        assertThat(r.message().variant()).isNull();
        assertThat(r.message().extraFields()).containsExactly("p1", "p2");
        assertThat(r.warnings()).extracting(DecodeWarning::field).containsOnly("CmdType");
        assertThat(SedapEncoder.encode(r.message())).isEqualTo(line);
    }

    @Test
    void missingRequiredFieldIsAWarning() {
        DecodeResult r = decoder.decode("ACKNOWLEDGE;18;;;;;;LASSY");

        assertThat(r.rejected()).isFalse();
        assertThat(r.warnings())
                .extracting(DecodeWarning::field)
                .containsExactlyInAnyOrder("TypeOfMessage", "NumberOfMessage");
    }

    @Test
    void contactWithoutAnyPositionIsFlagged() {
        DecodeResult r = decoder.decode("CONTACT;;;;;;;55;FALSE");

        assertThat(r.warnings()).extracting(DecodeWarning::message).anyMatch(m -> m.contains("neither"));
    }

    @Test
    void deleteNeedsOnlyTheId() {
        assertThat(decoder.decode("CONTACT;;;;;;;55;TRUE").clean()).isTrue();
    }

    @Test
    void invalidBase64IsKeptRaw() {
        DecodeResult r = decoder.decode("TEXT;;;;;;;;04;BASE64;not*base64!");

        assertThat(r.warnings()).extracting(DecodeWarning::field).containsExactly("Text");
    }

    @Test
    void overlongLineIsRejected() {
        DecodeResult r = new SedapDecoder(100).decode("TEXT;" + "x".repeat(200));

        assertThat(r.rejected()).isTrue();
    }

    @Test
    void emptyAndNullLinesAreRejected() {
        assertThat(decoder.decode("").rejected()).isTrue();
        assertThat(decoder.decode("\r\n").rejected()).isTrue();
        assertThat(decoder.decode(null).rejected()).isTrue();
    }

    @Test
    void base64GarbageThatIsNotDeflateIsRejected() {
        assertThat(decoder.decode("QUJDREVGRw==").rejected()).isTrue();
    }

    @Test
    void zipBombIsRejected() throws Exception {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9, true);
        byte[] input = ("TEXT;" + ";".repeat(5_000_000)).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        deflater.setInput(input);
        deflater.finish();
        byte[] buffer = new byte[input.length];
        int n = deflater.deflate(buffer);
        String compressed = java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer, n));

        assertThat(decoder.decode(compressed).rejected()).isTrue();
    }

    /** Random mutations of real samples: the decoder must never throw, whatever comes in. */
    @RepeatedTest(20)
    void fuzzedSamplesNeverThrow() throws Exception {
        Random random = new Random();
        String alphabet = ";#,.-+eE0123456789ABCDEFxyz TRUEFALSE=\n\r\u0000ÿ";
        for (IcdSamplesTest.Sample sample : IcdSamplesTest.all()) {
            StringBuilder mutated = new StringBuilder(sample.line());
            int edits = 1 + random.nextInt(8);
            for (int i = 0; i < edits && !mutated.isEmpty(); i++) {
                int pos = random.nextInt(mutated.length());
                switch (random.nextInt(3)) {
                    case 0 -> mutated.deleteCharAt(pos);
                    case 1 -> mutated.insert(pos, alphabet.charAt(random.nextInt(alphabet.length())));
                    default -> mutated.setCharAt(pos, alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }
            String input = mutated.toString();
            assertThatCode(() -> {
                        DecodeResult r = decoder.decode(input);
                        if (!r.rejected()) {
                            SedapEncoder.encode(r.message());
                        }
                    })
                    .as(input)
                    .doesNotThrowAnyException();
        }
    }
}
