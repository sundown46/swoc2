package io.swoc2.sedap.codec;

import io.swoc2.sedap.schema.FieldSpec;

/**
 * One decoded (or built) field.
 *
 * @param spec the field's schema entry
 * @param raw the text as received, or {@code null} for built messages. Re-encoding uses it
 *     unchanged, so a received message is forwarded byte-for-byte (SDX-001, loop prevention
 *     dedup keys stay stable)
 * @param value the parsed value, or {@code null} when empty or invalid (see {@link FieldCodec}
 *     for the Java type per {@link io.swoc2.sedap.schema.FieldKind})
 */
public record FieldValue(FieldSpec spec, String raw, Object value) {

    public String name() {
        return spec.name();
    }

    /** True when the field carries no usable value (empty, or invalid and kept raw only). */
    public boolean isEmpty() {
        return value == null;
    }
}
