package io.swoc2.sedap.codec;

import io.swoc2.sedap.schema.FieldKind;
import io.swoc2.sedap.schema.FieldSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the wire form of a {@link SedapMessage} (ICD §2, §5). Fields that came from the wire
 * are re-emitted exactly as received; built fields are formatted canonically. Trailing empty
 * fields are dropped ("excess trailing semicolons may be truncated", ICD §2). The line terminator
 * is not included - the transport adds it.
 */
public final class SedapEncoder {

    private SedapEncoder() {}

    public static String encode(SedapMessage message) {
        List<String> parts = new ArrayList<>();
        parts.add(message.type().name());
        parts.addAll(message.rawHeader() != null ? message.rawHeader() : formatHeader(message.header()));
        for (FieldValue field : message.fields()) {
            parts.add(field.raw() != null ? field.raw() : FieldCodec.format(field.spec(), field.value()));
        }
        parts.addAll(message.extraFields());
        int end = parts.size();
        while (end > 1 && parts.get(end - 1).isEmpty()) {
            end--;
        }
        return String.join(";", parts.subList(0, end));
    }

    private static List<String> formatHeader(Header header) {
        return List.of(
                header.number() == null ? "" : "%02X".formatted(header.number()),
                header.time() == null ? "" : FieldCodec.format(FieldSpec.of("Time", FieldKind.HEX_TIME), header.time()),
                header.sender() == null ? "" : header.sender(),
                header.classification() == null ? "" : header.classification().toString(),
                header.acknowledgement() ? "TRUE" : "",
                header.mac() == null ? "" : header.mac().toUpperCase(java.util.Locale.ROOT));
    }
}
