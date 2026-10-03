package io.swoc2.sedap.codec;

import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.schema.VariantSpec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A SEDAP-Express message in schema-driven form: header, then fields in wire order (fixed fields
 * of the type, then the type-dependent parameters of {@link #variant()} for GRAPHIC/COMMAND).
 * Immutable.
 *
 * @param type message type
 * @param header common header (ICD §5)
 * @param fields all known fields in wire order
 * @param variant the GRAPHIC shape / COMMAND type, or {@code null}
 * @param extraFields trailing fields beyond the schema, kept for forwarding (forward compatibility)
 * @param rawHeader header fields as received, or {@code null} for built messages
 */
public record SedapMessage(
        MessageType type,
        Header header,
        List<FieldValue> fields,
        VariantSpec variant,
        List<String> extraFields,
        List<String> rawHeader) {

    public SedapMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(header, "header");
        fields = List.copyOf(fields);
        extraFields = extraFields == null ? List.of() : List.copyOf(extraFields);
        rawHeader = rawHeader == null ? null : List.copyOf(rawHeader);
    }

    public static SedapMessageBuilder builder(MessageType type) {
        return new SedapMessageBuilder(type);
    }

    public Optional<FieldValue> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    /** Parsed value of a field, if present, valid and of the requested type. */
    public <T> Optional<T> value(String name, Class<T> type) {
        return field(name).map(FieldValue::value).filter(type::isInstance).map(type::cast);
    }

    public Optional<String> text(String name) {
        return value(name, String.class);
    }

    public Optional<Double> number(String name) {
        return value(name, Double.class);
    }

    /** Same message with a different header; drops the raw header so the new one is encoded. */
    public SedapMessage withHeader(Header newHeader) {
        return new SedapMessage(type, newHeader, fields, variant, extraFields, null);
    }
}
