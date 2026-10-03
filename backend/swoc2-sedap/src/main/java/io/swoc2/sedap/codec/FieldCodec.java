package io.swoc2.sedap.codec;

import io.swoc2.sedap.schema.FieldKind;
import io.swoc2.sedap.schema.FieldSpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses and formats single field values per {@link FieldKind}. Java value types:
 *
 * <ul>
 *   <li>TEXT, HEX, SIDC, SOURCE_CHARS, ENCODING: {@link String}
 *   <li>DOUBLE, LATITUDE, LONGITUDE, ANGLE: {@link Double}
 *   <li>INTEGER, CODE: {@link Integer}
 *   <li>HEX_TIME: {@link Instant}
 *   <li>BOOLEAN, ON_OFF: {@link Boolean}
 *   <li>BASE64: {@link Base64Data}
 *   <li>DOUBLE_LIST: {@code List<Double>}; LEVEL_LIST: {@code List<Level>}; TEXT_LIST:
 *       {@code List<String>}
 *   <li>COORDINATE: {@link Coordinate}; COORDINATE_LIST: {@code List<Coordinate>}
 * </ul>
 *
 * Parsing never throws: invalid input returns {@code null} and adds a warning.
 */
public final class FieldCodec {

    private FieldCodec() {}

    /** Plain decimal number; no NaN/Infinity/hex floats. Exponent tolerated. */
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d{1,3})?");

    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]+");
    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d{1,10}");
    private static final Pattern SIDC = Pattern.compile("[A-Za-z0-9*\\-]+");
    private static final String SOURCE_CHARS = "RAISEOYM";

    /** Plausible window for HEX_TIME values; outside it a warning flags a probably wrong unit. */
    private static final Instant TIME_MIN = Instant.parse("1990-01-01T00:00:00Z");

    private static final Instant TIME_MAX = Instant.parse("2200-01-01T00:00:00Z");

    /**
     * Parses one field. Empty input means "not given" (ICD §2: unknown values are sent empty) and
     * yields {@code null} without a warning; required-field checks happen at message level.
     */
    public static Object parse(FieldSpec spec, String raw, List<DecodeWarning> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        try {
            Object value = switch (spec.kind()) {
                case TEXT -> parseText(spec, raw, warnings);
                case DOUBLE, LATITUDE, LONGITUDE, ANGLE -> parseDouble(spec, s, warnings);
                case INTEGER -> parseInteger(spec, s, warnings);
                case CODE -> parseCode(spec, s, warnings);
                case HEX -> parseHex(spec, s, warnings);
                case HEX_TIME -> parseTime(spec, s, warnings);
                case BOOLEAN -> parseFlag(spec, s, "TRUE", "FALSE", warnings);
                case ON_OFF -> parseFlag(spec, s, "ON", "OFF", warnings);
                case ENCODING -> parseEncoding(spec, s, warnings);
                case BASE64 -> parseBase64(spec, s, warnings);
                case SIDC -> parseSidc(spec, s, warnings);
                case SOURCE_CHARS -> parseSource(spec, s, warnings);
                case DOUBLE_LIST -> parseDoubleList(spec, s, warnings);
                case LEVEL_LIST -> parseLevels(spec, s, warnings);
                case COORDINATE -> parseCoordinate(spec, s, s, warnings);
                case COORDINATE_LIST -> parseCoordinates(spec, s, warnings);
                case TEXT_LIST -> List.of(s.split("#", -1));
            };
            return value;
        } catch (RuntimeException unexpected) {
            // Defensive: a parser bug must degrade to "invalid field", never break the connection.
            warnings.add(new DecodeWarning(spec.name(), raw, "could not be parsed (" + unexpected + ")"));
            return null;
        }
    }

    private static String parseText(FieldSpec spec, String raw, List<DecodeWarning> warnings) {
        checkLength(spec, raw, warnings);
        if (!spec.options().isEmpty() && spec.options().stream().noneMatch(o -> o.equalsIgnoreCase(raw.strip()))) {
            warnings.add(new DecodeWarning(spec.name(), raw, "not one of " + spec.options() + " (kept)"));
        }
        return raw;
    }

    private static void checkLength(FieldSpec spec, String raw, List<DecodeWarning> warnings) {
        if (spec.maxBytes() != null && raw.getBytes(StandardCharsets.UTF_8).length > spec.maxBytes()) {
            warnings.add(new DecodeWarning(spec.name(), raw, "longer than " + spec.maxBytes() + " bytes (kept)"));
        }
    }

    private static Double parseDouble(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        String candidate = s;
        if (!DECIMAL.matcher(candidate).matches() && candidate.indexOf('.') < 0 && candidate.indexOf(',') > 0) {
            // A sender with a German locale wrote "53,32"; one comma and no dot is unambiguous.
            String swapped = candidate.replace(',', '.');
            if (DECIMAL.matcher(swapped).matches()) {
                warnings.add(new DecodeWarning(spec.name(), s, "decimal comma interpreted as decimal point"));
                candidate = swapped;
            }
        }
        if (!DECIMAL.matcher(candidate).matches()) {
            warnings.add(new DecodeWarning(spec.name(), s, "not a number"));
            return null;
        }
        double value = Double.parseDouble(candidate);
        if (!Double.isFinite(value)) {
            warnings.add(new DecodeWarning(spec.name(), s, "number out of range"));
            return null;
        }
        if (spec.kind() == FieldKind.LATITUDE || spec.kind() == FieldKind.LONGITUDE) {
            if (outOfRange(spec, value)) {
                warnings.add(new DecodeWarning(spec.name(), s, "outside " + rangeText(spec) + " (dropped)"));
                return null;
            }
        } else if (spec.kind() == FieldKind.ANGLE) {
            if (value < 0 || value >= 360) {
                warnings.add(new DecodeWarning(spec.name(), s, "outside [0, 360) (kept, normalised on mapping)"));
            }
        } else if (outOfRange(spec, value)) {
            warnings.add(new DecodeWarning(spec.name(), s, "outside " + rangeText(spec) + " (kept)"));
        }
        return value;
    }

    private static boolean outOfRange(FieldSpec spec, double value) {
        return (spec.min() != null && value < spec.min()) || (spec.max() != null && value > spec.max());
    }

    private static String rangeText(FieldSpec spec) {
        return "[" + fmt(spec.min()) + ", " + fmt(spec.max()) + "]";
    }

    private static String fmt(Double d) {
        return d == null ? "-inf" : formatDouble(d);
    }

    private static Integer parseInteger(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        if (!INTEGER.matcher(s).matches()) {
            warnings.add(new DecodeWarning(spec.name(), s, "not an integer"));
            return null;
        }
        long value = Long.parseLong(s);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            warnings.add(new DecodeWarning(spec.name(), s, "integer out of range"));
            return null;
        }
        if (outOfRange(spec, value)) {
            warnings.add(new DecodeWarning(spec.name(), s, "outside " + rangeText(spec) + " (kept)"));
        }
        if (!spec.codes().isEmpty() && !spec.codes().containsKey((int) value)) {
            warnings.add(new DecodeWarning(spec.name(), s, "unknown code (kept)"));
        }
        return (int) value;
    }

    private static Integer parseCode(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        // ICD writes codes as two hex digits ("04"); a single digit ("4", ICD §7 sample) is
        // accepted silently, more digits only if the value still fits a byte.
        if (!HEX.matcher(s).matches() || s.length() > 4) {
            warnings.add(new DecodeWarning(spec.name(), s, "not a hex code"));
            return null;
        }
        int code = Integer.parseInt(s, 16);
        if (code > 0xFF) {
            warnings.add(new DecodeWarning(spec.name(), s, "code larger than one byte"));
            return null;
        }
        if (!spec.codes().isEmpty() && !spec.codes().containsKey(code)) {
            warnings.add(new DecodeWarning(spec.name(), s, "unknown code (kept)"));
        }
        return code;
    }

    private static String parseHex(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        if (!HEX.matcher(s).matches()) {
            warnings.add(new DecodeWarning(spec.name(), s, "not a hex string"));
            return null;
        }
        checkLength(spec, s, warnings);
        return s;
    }

    private static Instant parseTime(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        if (!HEX.matcher(s).matches() || s.length() > 16) {
            warnings.add(new DecodeWarning(spec.name(), s, "not a hex timestamp"));
            return null;
        }
        long millis = Long.parseUnsignedLong(s, 16);
        if (millis < 0) {
            warnings.add(new DecodeWarning(spec.name(), s, "timestamp out of range"));
            return null;
        }
        Instant time = Instant.ofEpochMilli(millis);
        if (time.isBefore(TIME_MIN) || time.isAfter(TIME_MAX)) {
            warnings.add(new DecodeWarning(spec.name(), s, "implausible timestamp " + time + " (kept)"));
        }
        return time;
    }

    private static Boolean parseFlag(FieldSpec spec, String s, String yes, String no, List<DecodeWarning> warnings) {
        if (s.equalsIgnoreCase(yes)) {
            return Boolean.TRUE;
        }
        if (s.equalsIgnoreCase(no)) {
            return Boolean.FALSE;
        }
        if (s.equals("1") || s.equals("0")) {
            warnings.add(
                    new DecodeWarning(spec.name(), s, "numeric flag interpreted as " + (s.equals("1") ? yes : no)));
            return s.equals("1");
        }
        warnings.add(new DecodeWarning(spec.name(), s, "expected " + yes + " or " + no));
        return null;
    }

    private static String parseEncoding(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.equals("BASE64") || upper.equals("NONE")) {
            return upper;
        }
        warnings.add(new DecodeWarning(spec.name(), s, "expected BASE64 or NONE"));
        return null;
    }

    private static Base64Data parseBase64(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        checkLength(spec, s, warnings);
        if (decodeBase64(s) != null) {
            return new Base64Data(s);
        }
        warnings.add(new DecodeWarning(spec.name(), s, "not valid BASE64 (kept raw)"));
        return null;
    }

    /** Standard BASE64, missing padding tolerated. Returns {@code null} if invalid. */
    static byte[] decodeBase64(String s) {
        String padded = s.length() % 4 == 0 ? s : s + "=".repeat(4 - s.length() % 4);
        try {
            return Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String parseSidc(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        if (!SIDC.matcher(s).matches()) {
            warnings.add(new DecodeWarning(spec.name(), s, "invalid SIDC characters (dropped)"));
            return null;
        }
        if (s.length() != 15) {
            warnings.add(new DecodeWarning(spec.name(), s, "SIDC is not 15 characters long (kept)"));
        }
        return s;
    }

    private static String parseSource(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        String upper = s.toUpperCase(Locale.ROOT);
        for (char c : upper.toCharArray()) {
            if (SOURCE_CHARS.indexOf(c) < 0) {
                warnings.add(new DecodeWarning(spec.name(), s, "unknown source character '" + c + "' (kept)"));
                break;
            }
        }
        return upper;
    }

    private static List<Double> parseDoubleList(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        List<Double> values = new ArrayList<>();
        for (String element : s.split("#")) {
            if (element.isBlank()) {
                continue;
            }
            if (!DECIMAL.matcher(element.strip()).matches()) {
                warnings.add(new DecodeWarning(spec.name(), s, "list element '" + element + "' is not a number"));
                return null;
            }
            values.add(Double.parseDouble(element.strip()));
        }
        return List.copyOf(values);
    }

    private static List<Level> parseLevels(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        String[] parts = s.split("#", -1);
        // A trailing '#' (ICD shows "<name>#<level>#...") yields one empty last element.
        int count = parts.length % 2 == 1 && parts[parts.length - 1].isEmpty() ? parts.length - 1 : parts.length;
        if (count % 2 != 0) {
            warnings.add(new DecodeWarning(spec.name(), s, "odd number of name#level elements"));
            return null;
        }
        List<Level> levels = new ArrayList<>();
        for (int i = 0; i < count; i += 2) {
            String level = parts[i + 1].strip();
            if (!DECIMAL.matcher(level).matches()) {
                warnings.add(new DecodeWarning(spec.name(), s, "level '" + level + "' is not a number"));
                return null;
            }
            double percent = Double.parseDouble(level);
            if (percent < 0 || percent > 100) {
                warnings.add(new DecodeWarning(spec.name(), s, "level " + level + " outside [0, 100] (kept)"));
            }
            levels.add(new Level(parts[i], percent));
        }
        return List.copyOf(levels);
    }

    private static Coordinate parseCoordinate(
            FieldSpec spec, String element, String whole, List<DecodeWarning> warnings) {
        String[] parts = element.split(",", -1);
        if (parts.length < 2 || parts.length > 3) {
            warnings.add(new DecodeWarning(spec.name(), whole, "coordinate '" + element + "' is not lat,lon[,alt]"));
            return null;
        }
        for (String part : parts) {
            if (!part.isBlank() && !DECIMAL.matcher(part.strip()).matches()) {
                warnings.add(new DecodeWarning(spec.name(), whole, "coordinate '" + element + "' is not numeric"));
                return null;
            }
        }
        if (parts[0].isBlank() || parts[1].isBlank()) {
            warnings.add(new DecodeWarning(spec.name(), whole, "coordinate '" + element + "' lacks lat or lon"));
            return null;
        }
        double lat = Double.parseDouble(parts[0].strip());
        double lon = Double.parseDouble(parts[1].strip());
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            warnings.add(new DecodeWarning(spec.name(), whole, "coordinate '" + element + "' out of range"));
            return null;
        }
        Double alt = parts.length == 3 && !parts[2].isBlank() ? Double.parseDouble(parts[2].strip()) : null;
        return new Coordinate(lat, lon, alt);
    }

    private static List<Coordinate> parseCoordinates(FieldSpec spec, String s, List<DecodeWarning> warnings) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (String element : s.split("#")) {
            if (element.isBlank()) {
                continue;
            }
            Coordinate c = parseCoordinate(spec, element.strip(), s, warnings);
            if (c == null) {
                return null;
            }
            coordinates.add(c);
        }
        return List.copyOf(coordinates);
    }

    // --- Generation ------------------------------------------------------------------------

    /**
     * Checks that a value has the right Java type and range for its field, for message
     * generation. Returns an error text, or {@code null} if the value is acceptable.
     */
    public static String validate(FieldSpec spec, Object value) {
        if (value == null) {
            return null;
        }
        return switch (spec.kind()) {
            case TEXT ->
                !(value instanceof String s)
                        ? wrongType(spec, value, "String")
                        : !spec.options().isEmpty() && !spec.options().contains(s)
                                ? "must be one of " + spec.options()
                                : checkText(spec, s);
            case HEX ->
                value instanceof String s && HEX.matcher(s).matches() ? checkText(spec, s) : "must be a hex string";
            case SIDC ->
                value instanceof String s && SIDC.matcher(s).matches() && s.length() == 15
                        ? null
                        : "must be a 15-character SIDC";
            case SOURCE_CHARS ->
                value instanceof String s && !s.isEmpty() && s.chars().allMatch(c -> SOURCE_CHARS.indexOf(c) >= 0)
                        ? null
                        : "must be one or more of " + SOURCE_CHARS;
            case ENCODING -> "BASE64".equals(value) || "NONE".equals(value) ? null : "must be BASE64 or NONE";
            case DOUBLE, LATITUDE, LONGITUDE, ANGLE ->
                value instanceof Double d
                        ? (!Double.isFinite(d)
                                ? "must be finite"
                                : outOfRange(spec, d) ? "must be within " + rangeText(spec) : null)
                        : wrongType(spec, value, "Double");
            case INTEGER, CODE ->
                value instanceof Integer i
                        ? (spec.kind() == FieldKind.CODE && (i < 0 || i > 0xFF)
                                ? "must be 0..FF"
                                : !spec.codes().isEmpty() && !spec.codes().containsKey(i) ? "unknown code " + i : null)
                        : wrongType(spec, value, "Integer");
            case HEX_TIME -> value instanceof Instant t && t.toEpochMilli() >= 0 ? null : "must be an Instant >= epoch";
            case BOOLEAN, ON_OFF -> value instanceof Boolean ? null : wrongType(spec, value, "Boolean");
            case BASE64 ->
                value instanceof Base64Data b && decodeBase64(b.encoded()) != null ? null : "must be valid Base64Data";
            case DOUBLE_LIST, LEVEL_LIST, COORDINATE_LIST, TEXT_LIST ->
                value instanceof List<?> ? null : "must be a List";
            case COORDINATE -> value instanceof Coordinate ? null : wrongType(spec, value, "Coordinate");
        };
    }

    private static String checkText(FieldSpec spec, String s) {
        if (s.indexOf(';') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            // ICD §2: such content must be BASE64-encoded; refuse to emit a corrupt line.
            return "must not contain ';' or line breaks (use BASE64)";
        }
        if (spec.maxBytes() != null && s.getBytes(StandardCharsets.UTF_8).length > spec.maxBytes()) {
            return "longer than " + spec.maxBytes() + " bytes";
        }
        return null;
    }

    private static String wrongType(FieldSpec spec, Object value, String expected) {
        return "expected " + expected + " for " + spec.kind() + ", got "
                + value.getClass().getSimpleName();
    }

    /** Formats a valid value for the wire. {@code null} becomes the empty string. */
    public static String format(FieldSpec spec, Object value) {
        if (value == null) {
            return "";
        }
        return switch (spec.kind()) {
            case TEXT, HEX, SIDC, SOURCE_CHARS, ENCODING -> (String) value;
            case DOUBLE, LATITUDE, LONGITUDE, ANGLE -> formatDouble((Double) value);
            case INTEGER -> Integer.toString((Integer) value);
            case CODE -> "%02X".formatted((Integer) value);
            // 12 hex digits as in every ICD sample (covers dates up to the year 10889).
            case HEX_TIME -> HexFormat.of().withUpperCase().toHexDigits(((Instant) value).toEpochMilli(), 12);
            case BOOLEAN -> (Boolean) value ? "TRUE" : "FALSE";
            case ON_OFF -> (Boolean) value ? "ON" : "OFF";
            case BASE64 -> ((Base64Data) value).encoded();
            case DOUBLE_LIST -> join(value, o -> formatDouble((Double) o));
            case LEVEL_LIST -> join(value, o -> ((Level) o).name() + "#" + formatDouble(((Level) o).percent()));
            case COORDINATE -> formatCoordinate((Coordinate) value);
            case COORDINATE_LIST -> join(value, o -> formatCoordinate((Coordinate) o));
            case TEXT_LIST -> join(value, o -> (String) o);
        };
    }

    private static String join(Object list, java.util.function.Function<Object, String> element) {
        return ((List<?>) list)
                .stream().map(element).reduce((a, b) -> a + "#" + b).orElse("");
    }

    private static String formatCoordinate(Coordinate c) {
        return formatDouble(c.latitude())
                + ","
                + formatDouble(c.longitude())
                + (c.altitude() == null ? "" : "," + formatDouble(c.altitude()));
    }

    /** Shortest exact plain-decimal form: {@code 53.32}, {@code 0}, never {@code 5.0E-4}. */
    static String formatDouble(double value) {
        if (value == 0) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
