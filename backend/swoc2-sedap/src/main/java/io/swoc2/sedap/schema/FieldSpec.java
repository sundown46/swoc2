package io.swoc2.sedap.schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One field of a SEDAP-Express message or of a type-dependent parameter list.
 *
 * @param name ICD field name, e.g. {@code Latitude}; unique within its message/variant
 * @param kind wire type
 * @param unit SI unit as written in the ICD ({@code m}, {@code m/s}, {@code deg}, ...), or
 *     {@code null} when unitless
 * @param required marked {@code (M)} in the ICD. Missing required fields produce a warning,
 *     never a decode failure (SDX-001)
 * @param min lower bound for numeric kinds, or {@code null}
 * @param max upper bound for numeric kinds, or {@code null}
 * @param maxBytes maximum length in bytes for text-like kinds, or {@code null}
 * @param codes code table for {@link FieldKind#CODE} and {@link FieldKind#INTEGER} (code to label),
 *     else empty
 * @param options allowed values for a {@link FieldKind#TEXT} field (case-insensitive), else
 *     empty; the first option is the one SWOC2 sends by default
 * @param pick how the wizard can fill this field from the map (TSK-003)
 * @param group fields with the same non-null group form one input (e.g. a position)
 * @param description short human-readable description (for wizard labels and the CAC)
 */
public record FieldSpec(
        String name,
        FieldKind kind,
        String unit,
        boolean required,
        Double min,
        Double max,
        Integer maxBytes,
        Map<Integer, String> codes,
        List<String> options,
        PickKind pick,
        String group,
        String description) {

    public FieldSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        codes = codes == null ? Map.of() : Map.copyOf(codes);
        options = options == null ? List.of() : List.copyOf(options);
        pick = pick == null ? PickKind.NONE : pick;
    }

    /** Starts a field definition; the fluent {@code with*} methods return modified copies. */
    public static FieldSpec of(String name, FieldKind kind) {
        return new FieldSpec(name, kind, null, false, null, null, null, Map.of(), List.of(), PickKind.NONE, null, null);
    }

    /** Marks the field {@code (M)} (ICD). */
    public FieldSpec mandatory() {
        return new FieldSpec(name, kind, unit, true, min, max, maxBytes, codes, options, pick, group, description);
    }

    public FieldSpec unit(String newUnit) {
        return new FieldSpec(
                name, kind, newUnit, required, min, max, maxBytes, codes, options, pick, group, description);
    }

    public FieldSpec range(double newMin, double newMax) {
        return new FieldSpec(
                name, kind, unit, required, newMin, newMax, maxBytes, codes, options, pick, group, description);
    }

    public FieldSpec maxBytes(int newMaxBytes) {
        return new FieldSpec(
                name, kind, unit, required, min, max, newMaxBytes, codes, options, pick, group, description);
    }

    public FieldSpec codes(Map<Integer, String> newCodes) {
        return new FieldSpec(
                name, kind, unit, required, min, max, maxBytes, newCodes, options, pick, group, description);
    }

    public FieldSpec options(String... newOptions) {
        return new FieldSpec(
                name, kind, unit, required, min, max, maxBytes, codes, List.of(newOptions), pick, group, description);
    }

    public FieldSpec pick(PickKind newPick, String newGroup) {
        return new FieldSpec(
                name, kind, unit, required, min, max, maxBytes, codes, options, newPick, newGroup, description);
    }

    public FieldSpec describe(String newDescription) {
        return new FieldSpec(
                name, kind, unit, required, min, max, maxBytes, codes, options, pick, group, newDescription);
    }
}
