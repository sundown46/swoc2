package io.swoc2.sedap.schema;

import java.util.List;
import java.util.Objects;

/**
 * Type-dependent tail of a message: one GRAPHIC shape (ICD §6.7) or one COMMAND type (ICD §6.8).
 *
 * @param code wire code of the discriminator, e.g. {@code 0x24} for "Move to"
 * @param name ICD name, e.g. {@code Move to}
 * @param params parameters following the discriminator, in wire order
 * @param category grouping for the tasking wizard (e.g. {@code Movement}); {@code null} for
 *     GRAPHIC
 * @param confirm the wizard must ask for an explicit second confirmation before sending
 *     (destructive or weapon-related commands)
 */
public record VariantSpec(int code, String name, List<FieldSpec> params, String category, boolean confirm) {

    public VariantSpec {
        Objects.requireNonNull(name, "name");
        params = List.copyOf(params);
    }

    /** Hex code as written on the wire: two upper-case digits. */
    public String wireCode() {
        return "%02X".formatted(code);
    }
}
