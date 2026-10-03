package io.swoc2.sedap.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.schema.CommandSchemas;
import io.swoc2.sedap.schema.FieldSpec;
import io.swoc2.sedap.schema.GraphicSchemas;
import io.swoc2.sedap.schema.MessageSchema;
import io.swoc2.sedap.schema.SedapSchemas;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Generation round trip for every message type, every COMMAND type and every GRAPHIC shape:
 * build a message with every field populated, encode, decode, and get exactly the same values
 * back with no warnings (SDX-001).
 */
class RoundTripTest {

    private final SedapDecoder decoder = new SedapDecoder();

    static Stream<Arguments> everyTypeAndVariant() {
        List<Arguments> cases = new ArrayList<>();
        for (MessageType type : MessageType.values()) {
            MessageSchema schema = SedapSchemas.schemaFor(type);
            if (schema.discriminator() == null) {
                cases.add(Arguments.of(type, null));
            }
        }
        CommandSchemas.variants().keySet().forEach(code -> cases.add(Arguments.of(MessageType.COMMAND, code)));
        GraphicSchemas.variants().keySet().forEach(code -> cases.add(Arguments.of(MessageType.GRAPHIC, code)));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyTypeAndVariant")
    void fullyPopulatedMessageRoundTrips(MessageType type, Integer variantCode) {
        MessageSchema schema = SedapSchemas.schemaFor(type);
        SedapMessageBuilder builder = SedapMessage.builder(type)
                .number(0x5E)
                .time(Instant.parse("2026-10-03T00:00:00.123Z"))
                .sender("SWOC2")
                .classification('R')
                .acknowledgement(true);
        if (variantCode != null) {
            builder.variant(variantCode);
        }
        int seed = 0;
        List<FieldSpec> specs = new ArrayList<>(schema.fields());
        if (variantCode != null) {
            specs.addAll(schema.variant(variantCode).orElseThrow().params());
        }
        for (FieldSpec spec : specs) {
            if (spec.name().equals(schema.discriminator()) || skip(type, spec)) {
                continue;
            }
            builder.set(spec.name(), SampleValues.valueFor(spec, seed++));
        }
        SedapMessage built = builder.build();

        String line = SedapEncoder.encode(built);
        DecodeResult decoded = decoder.decode(line + "\r\n");

        assertThat(decoded.warnings()).as(line).isEmpty();
        SedapMessage back = decoded.message();
        assertThat(back.header()).isEqualTo(built.header());
        assertThat(back.variant()).isEqualTo(built.variant());
        for (FieldValue field : built.fields()) {
            assertThat(back.field(field.name()).orElseThrow().value())
                    .as("%s.%s in %s", type, field.name(), line)
                    .isEqualTo(field.value());
        }
        assertThat(SedapEncoder.encode(back)).isEqualTo(line);
    }

    /** CONTACT/POINT: send absolute position only; relative X/Y/Z is the alternative. */
    private static boolean skip(MessageType type, FieldSpec spec) {
        return (type == MessageType.CONTACT || type == MessageType.POINT)
                && spec.name().startsWith("rel");
    }

    @Test
    void relativePositionContactRoundTrips() {
        SedapMessage built = SedapMessage.builder(MessageType.POINT)
                .set("PointID", "1000")
                .set("DeleteFlag", false)
                .set("relX-Distance", 100)
                .set("relY-Distance", 130)
                .set("relZ-Distance", 0)
                .set("Name", "Person in water")
                .set("SIDC", "gfopep---------")
                .build();

        DecodeResult decoded = decoder.decode(SedapEncoder.encode(built));

        assertThat(decoded.clean()).as("%s", decoded.warnings()).isTrue();
        assertThat(decoded.message().number("relX-Distance")).contains(100.0);
    }

    @Test
    void cancelAllCommandNeedsNoCmdType() {
        SedapMessage built = SedapMessage.builder(MessageType.COMMAND)
                .number(0x29)
                .set("Recipient", "Drone2")
                .set("CmdID", "0000")
                .set("CmdFlag", 0x03)
                .build();

        String line = SedapEncoder.encode(built);

        assertThat(line).isEqualTo("COMMAND;29;;;;;;Drone2;0000;03");
        assertThat(decoder.decode(line).clean()).isTrue();
    }
}
