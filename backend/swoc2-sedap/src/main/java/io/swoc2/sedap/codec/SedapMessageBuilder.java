package io.swoc2.sedap.codec;

import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.schema.FieldSpec;
import io.swoc2.sedap.schema.MessageSchema;
import io.swoc2.sedap.schema.SedapSchemas;
import io.swoc2.sedap.schema.VariantSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds outgoing messages, strictly: unlike decoding, generation rejects wrong types, out-of-range
 * values, unknown field names and missing required fields with {@link IllegalArgumentException}.
 * What SWOC2 sends must always be valid ICD (inputs are validated at the REST boundary before
 * they get here; this is the last line of defence).
 */
public final class SedapMessageBuilder {

    private final MessageType type;
    private final MessageSchema schema;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private VariantSpec variant;
    private Integer number;
    private Instant time;
    private String sender;
    private Character classification;
    private boolean acknowledgement;
    private String mac;

    SedapMessageBuilder(MessageType type) {
        this.type = Objects.requireNonNull(type, "type");
        this.schema = SedapSchemas.schemaFor(type);
    }

    /** 7-bit message number (ICD §5). */
    public SedapMessageBuilder number(int value) {
        if (value < 0 || value > 0x7F) {
            throw new IllegalArgumentException("Number must be 0..7F, was " + value);
        }
        this.number = value;
        return this;
    }

    public SedapMessageBuilder time(Instant value) {
        this.time = value;
        return this;
    }

    public SedapMessageBuilder sender(String value) {
        if (value != null && (value.indexOf(';') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Sender must not contain ';' or line breaks");
        }
        this.sender = value;
        return this;
    }

    public SedapMessageBuilder classification(char value) {
        if ("PURCST".indexOf(value) < 0) {
            throw new IllegalArgumentException("Classification must be one of P, U, R, C, S, T");
        }
        this.classification = value;
        return this;
    }

    public SedapMessageBuilder acknowledgement(boolean value) {
        this.acknowledgement = value;
        return this;
    }

    /**
     * Selects the GRAPHIC shape / COMMAND type and sets the discriminator field accordingly.
     * Must be called before setting type-dependent parameters.
     */
    public SedapMessageBuilder variant(int code) {
        if (schema.discriminator() == null) {
            throw new IllegalArgumentException(type + " has no type-dependent parameters");
        }
        this.variant = schema.variant(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown " + schema.discriminator() + " " + code));
        values.put(schema.discriminator(), code);
        return this;
    }

    /** Sets a field by its ICD name (fixed field or parameter of the selected variant). */
    public SedapMessageBuilder set(String name, Object value) {
        FieldSpec spec = spec(name);
        Object normalised = value instanceof Number n && isFloating(spec) ? Double.valueOf(n.doubleValue()) : value;
        String error = FieldCodec.validate(spec, normalised);
        if (error != null) {
            throw new IllegalArgumentException(type + "." + name + ": " + error);
        }
        if (name.equals(schema.discriminator())) {
            throw new IllegalArgumentException("Use variant(code) to set " + name);
        }
        values.put(name, normalised);
        return this;
    }

    private static boolean isFloating(FieldSpec spec) {
        return switch (spec.kind()) {
            case DOUBLE, LATITUDE, LONGITUDE, ANGLE -> true;
            default -> false;
        };
    }

    private FieldSpec spec(String name) {
        return schema.field(name)
                .or(() -> variant == null
                        ? java.util.Optional.empty()
                        : variant.params().stream()
                                .filter(p -> p.name().equals(name))
                                .findFirst())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown field " + name + " for " + type + (variant == null ? "" : " " + variant.name())));
    }

    public SedapMessage build() {
        if (acknowledgement && number == null) {
            throw new IllegalArgumentException("Acknowledgement requires a message Number (ICD §5)");
        }
        boolean delete = Boolean.TRUE.equals(values.get("DeleteFlag"));
        List<FieldValue> fields = new ArrayList<>();
        for (FieldSpec spec : schema.fields()) {
            fields.add(new FieldValue(spec, null, values.get(spec.name())));
        }
        if (schema.discriminator() != null && variant == null) {
            boolean cancelAll =
                    type == MessageType.COMMAND && Integer.valueOf(0x03).equals(values.get("CmdFlag"));
            if (!cancelAll) {
                throw new IllegalArgumentException(type + " requires variant(...) (" + schema.discriminator() + ")");
            }
        }
        if (variant != null) {
            for (FieldSpec spec : variant.params()) {
                fields.add(new FieldValue(spec, null, values.get(spec.name())));
            }
        }
        for (FieldValue field : fields) {
            if (field.spec().required() && field.value() == null && !delete && !positionPairSatisfied(field)) {
                throw new IllegalArgumentException(type + "." + field.name() + " is required");
            }
        }
        if (mac != null && !mac.matches("[0-9A-Fa-f]{1,64}")) {
            throw new IllegalArgumentException("MAC must be hex");
        }
        return new SedapMessage(
                type,
                new Header(number, time, sender, classification, acknowledgement, mac),
                fields,
                variant,
                null,
                null);
    }

    /** CONTACT/POINT: Lat/Lon and rel X/Y/Z are alternatives (ICD §6.2/§6.3). */
    private boolean positionPairSatisfied(FieldValue field) {
        if (type != MessageType.CONTACT && type != MessageType.POINT) {
            return false;
        }
        boolean absolute = values.get("Latitude") != null && values.get("Longitude") != null;
        boolean relative = values.get("relX-Distance") != null && values.get("relY-Distance") != null;
        return switch (field.name()) {
            case "Latitude", "Longitude" -> relative;
            case "relX-Distance", "relY-Distance", "relZ-Distance" ->
                absolute || (relative && field.name().equals("relZ-Distance"));
            default -> false;
        };
    }
}
