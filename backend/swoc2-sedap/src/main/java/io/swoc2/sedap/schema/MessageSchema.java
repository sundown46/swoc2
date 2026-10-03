package io.swoc2.sedap.schema;

import io.swoc2.sedap.MessageType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Content fields of one message type, after the common header (ICD §5).
 *
 * @param type message type
 * @param icdSection ICD section, for log messages and docs ({@code §6.2})
 * @param fields fixed fields in wire order. If {@code discriminator} is set, it names one of
 *     these, and {@link #variants()} describes what follows it
 * @param discriminator name of the field whose value selects the variant, or {@code null}
 * @param variants type-dependent parameter lists by discriminator code
 */
public record MessageSchema(
        MessageType type,
        String icdSection,
        List<FieldSpec> fields,
        String discriminator,
        Map<Integer, VariantSpec> variants) {

    public MessageSchema {
        Objects.requireNonNull(type, "type");
        fields = List.copyOf(fields);
        variants = variants == null ? Map.of() : Map.copyOf(variants);
        if (discriminator != null && fields.stream().noneMatch(f -> f.name().equals(discriminator))) {
            throw new IllegalArgumentException("Discriminator " + discriminator + " is not a field of " + type);
        }
    }

    public Optional<VariantSpec> variant(int code) {
        return Optional.ofNullable(variants.get(code));
    }

    public Optional<FieldSpec> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
