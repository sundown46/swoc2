package io.swoc2.sedap.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.swoc2.sedap.MessageType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Consistency of the declarative schemas the tasking wizard will be generated from (TSK-002). */
class CommandSchemasTest {

    /** Every CmdType code listed in ICD §6.8. */
    private static final List<Integer> ICD_COMMAND_CODES = List.of(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x30, 0x31, 0x32, 0x33, 0x34,
            0x35, 0x36, 0x37, 0x40, 0x41, 0x42, 0x43, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0xEE, 0xEF, 0xFF);

    @Test
    void everyIcdCommandTypeIsDescribed() {
        assertThat(CommandSchemas.variants().keySet()).containsExactlyElementsOf(ICD_COMMAND_CODES);
    }

    @Test
    void everyMessageTypeHasASchema() {
        for (MessageType type : MessageType.values()) {
            assertThat(SedapSchemas.schemaFor(type)).as("%s", type).isNotNull();
        }
    }

    @Test
    void graphicShapesMatchIcd() {
        assertThat(GraphicSchemas.variants().keySet())
                .containsExactly(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B);
    }

    @Test
    void parameterNamesAreUniquePerVariant() {
        for (VariantSpec v : CommandSchemas.variants().values()) {
            Set<String> names = new HashSet<>();
            v.params()
                    .forEach(p -> assertThat(names.add(p.name()))
                            .as("%s.%s", v.name(), p.name())
                            .isTrue());
        }
    }

    @Test
    void everyPickableLatitudeHasALongitudeInTheSameGroup() {
        for (VariantSpec v : CommandSchemas.variants().values()) {
            Map<String, Set<PickKind>> byGroup = v.params().stream()
                    .filter(p -> p.group() != null)
                    .collect(Collectors.groupingBy(
                            FieldSpec::group, Collectors.mapping(FieldSpec::pick, Collectors.toSet())));
            byGroup.forEach((group, picks) -> assertThat(picks)
                    .as("%s group %s", v.name(), group)
                    .contains(PickKind.LATITUDE, PickKind.LONGITUDE));
        }
    }

    @Test
    void weaponsAndDestructiveCommandsNeedConfirmation() {
        CommandSchemas.variants().values().stream()
                .filter(v -> v.category().equals(CommandSchemas.WEAPON))
                .forEach(v -> assertThat(v.confirm()).as(v.name()).isTrue());
        assertThat(CommandSchemas.variants().get(0xEF).confirm()).isTrue();
        assertThat(CommandSchemas.variants().get(0xEE).confirm()).isTrue();
        assertThat(CommandSchemas.variants().get(0x16).confirm()).isTrue();
        assertThat(CommandSchemas.variants().get(0x24).confirm()).isFalse();
    }

    @Test
    void moveToIsMapPickable() {
        VariantSpec moveTo = CommandSchemas.variants().get(0x24);
        assertThat(moveTo.params())
                .extracting(FieldSpec::pick)
                .containsExactly(
                        PickKind.LATITUDE, PickKind.LONGITUDE, PickKind.ALTITUDE, PickKind.NONE, PickKind.NONE);
        assertThat(moveTo.params().getFirst().unit()).isEqualTo("deg");
        assertThat(moveTo.wireCode()).isEqualTo("24");
    }
}
