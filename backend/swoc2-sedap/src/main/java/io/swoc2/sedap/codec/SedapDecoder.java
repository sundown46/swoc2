package io.swoc2.sedap.codec;

import io.swoc2.sedap.MessageType;
import io.swoc2.sedap.schema.FieldSpec;
import io.swoc2.sedap.schema.MessageSchema;
import io.swoc2.sedap.schema.SedapSchemas;
import io.swoc2.sedap.schema.VariantSpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Tolerant decoder for one SEDAP-Express line (ICD §2, §5, §6; SDX-001). Thread-safe and
 * stateless; {@link #decode(String)} never throws.
 *
 * <p>The caller (a connection) is responsible for framing and charset: it hands over one line,
 * decoded as ISO-8859-1 (ICD §1 "ASCII"), with or without the terminator.
 */
public final class SedapDecoder {

    /** Default guard against absurd lines; TCP has no ICD size limit, so this is generous. */
    public static final int DEFAULT_MAX_LINE_CHARS = 1 << 20;

    /** Upper bound for inflated compressed messages (zip-bomb guard). */
    private static final int MAX_INFLATED_BYTES = 1 << 20;

    private static final Pattern BASE64_LINE = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private final int maxLineChars;

    public SedapDecoder() {
        this(DEFAULT_MAX_LINE_CHARS);
    }

    public SedapDecoder(int maxLineChars) {
        this.maxLineChars = maxLineChars;
    }

    public DecodeResult decode(String line) {
        try {
            return decodeInternal(line);
        } catch (RuntimeException unexpected) {
            // Last-resort barrier (CLAUDE.md principle 1): a decoder bug drops one line, nothing more.
            return new DecodeResult(
                    line, null, List.of(DecodeWarning.message("decoder failure: " + unexpected)), false);
        }
    }

    private DecodeResult decodeInternal(String line) {
        if (line == null) {
            return rejected("", "null line", false);
        }
        if (line.length() > maxLineChars) {
            return rejected(line.substring(0, 200), "line longer than " + maxLineChars + " characters", false);
        }
        String stripped = stripTerminator(line);
        if (stripped.isBlank()) {
            return rejected(stripped, "empty line", false);
        }
        int firstSeparator = stripped.indexOf(';');
        String name = firstSeparator < 0 ? stripped : stripped.substring(0, firstSeparator);
        Optional<MessageType> type = MessageType.fromWireName(name);
        if (type.isEmpty()) {
            // ICD §3.3: "if the first bytes don't match a message name, test for compression".
            Optional<String> inflated = tryInflate(stripped);
            if (inflated.isPresent()) {
                DecodeResult inner = decodeUncompressed(stripTerminator(inflated.get()));
                return new DecodeResult(inner.raw(), inner.message(), inner.warnings(), true);
            }
            return rejected(stripped, "unknown message name '" + truncate(name) + "'", false);
        }
        return decodeUncompressed(stripped);
    }

    private DecodeResult decodeUncompressed(String line) {
        List<String> parts = Arrays.asList(line.split(";", -1));
        Optional<MessageType> type = MessageType.fromWireName(parts.getFirst());
        if (type.isEmpty()) {
            return rejected(line, "unknown message name '" + truncate(parts.getFirst()) + "'", true);
        }
        List<DecodeWarning> warnings = new ArrayList<>();
        if (!parts.getFirst().equals(type.get().name())) {
            warnings.add(DecodeWarning.message("message name '" + parts.getFirst() + "' is not upper case"));
        }
        List<String> rawHeader = new ArrayList<>(6);
        for (int i = 1; i <= 6; i++) {
            rawHeader.add(i < parts.size() ? parts.get(i) : "");
        }
        Header header = decodeHeader(rawHeader, warnings);
        List<String> content = parts.size() > 7 ? parts.subList(7, parts.size()) : List.of();

        MessageSchema schema = SedapSchemas.schemaFor(type.get());
        List<FieldValue> fields = new ArrayList<>();
        int index = 0;
        for (FieldSpec spec : schema.fields()) {
            String raw = index < content.size() ? content.get(index) : "";
            fields.add(new FieldValue(spec, raw, FieldCodec.parse(spec, raw, warnings)));
            index++;
        }
        VariantSpec variant = null;
        if (schema.discriminator() != null) {
            Object code = fields.stream()
                    .filter(f -> f.name().equals(schema.discriminator()))
                    .findFirst()
                    .map(FieldValue::value)
                    .orElse(null);
            if (code instanceof Integer c) {
                variant = schema.variant(c).orElse(null);
                if (variant == null) {
                    warnings.add(new DecodeWarning(
                            schema.discriminator(), Integer.toHexString(c), "unknown type, parameters kept raw"));
                }
            }
            if (variant != null) {
                for (FieldSpec spec : variant.params()) {
                    String raw = index < content.size() ? content.get(index) : "";
                    fields.add(new FieldValue(spec, raw, FieldCodec.parse(spec, raw, warnings)));
                    index++;
                }
            }
        }
        List<String> extra = index < content.size() ? content.subList(index, content.size()) : List.of();
        if (extra.stream().anyMatch(s -> !s.isBlank())) {
            if (schema.discriminator() == null || variant != null) {
                warnings.add(DecodeWarning.message(extra.size() + " field(s) beyond the ICD schema (kept)"));
            }
        }
        SedapMessage message = new SedapMessage(type.get(), header, fields, variant, extra, rawHeader);
        checkMessageRules(message, warnings);
        return new DecodeResult(line, message, warnings, false);
    }

    private static Header decodeHeader(List<String> raw, List<DecodeWarning> warnings) {
        Integer number = null;
        String n = raw.get(0).strip();
        if (!n.isEmpty()) {
            if (n.matches("[0-9A-Fa-f]{1,2}")) {
                number = Integer.parseInt(n, 16);
                if (number > 0x7F) {
                    warnings.add(new DecodeWarning("Number", n, "above 7F (kept)"));
                }
            } else {
                warnings.add(new DecodeWarning("Number", n, "not a two-digit hex number"));
            }
        }
        Instant time = null;
        String t = raw.get(1).strip();
        if (!t.isEmpty()) {
            List<DecodeWarning> timeWarnings = new ArrayList<>();
            Object parsed =
                    FieldCodec.parse(FieldSpec.of("Time", io.swoc2.sedap.schema.FieldKind.HEX_TIME), t, timeWarnings);
            time = parsed instanceof Instant i ? i : null;
            warnings.addAll(timeWarnings);
        }
        String sender = raw.get(2).isBlank() ? null : raw.get(2);
        Character classification = null;
        String c = raw.get(3).strip();
        if (!c.isEmpty()) {
            char ch = Character.toUpperCase(c.charAt(0));
            if (c.length() == 1 && "PURCST".indexOf(ch) >= 0) {
                classification = ch;
            } else {
                warnings.add(new DecodeWarning("Classification", c, "expected one of P, U, R, C, S, T"));
            }
        }
        boolean ack = false;
        String a = raw.get(4).strip();
        if (!a.isEmpty()) {
            if (a.equalsIgnoreCase("TRUE")) {
                ack = true;
            } else if (!a.equalsIgnoreCase("FALSE")) {
                warnings.add(new DecodeWarning("Acknowledgement", a, "expected TRUE, FALSE or empty"));
            }
        }
        String mac = null;
        String m = raw.get(5).strip();
        if (!m.isEmpty()) {
            if (m.matches("[0-9A-Fa-f]{1,64}")) {
                mac = m;
            } else {
                warnings.add(new DecodeWarning("MAC", m, "not a hex MAC"));
            }
        }
        if (ack && number == null) {
            warnings.add(new DecodeWarning("Acknowledgement", a, "requested without a message Number (ICD §5)"));
        }
        return new Header(number, time, sender, classification, ack, mac);
    }

    /** Message-level rules that one field's schema cannot express. */
    private static void checkMessageRules(SedapMessage message, List<DecodeWarning> warnings) {
        MessageType type = message.type();
        boolean isDelete = message.value("DeleteFlag", Boolean.class).orElse(false);
        for (FieldValue field : message.fields()) {
            if (!field.spec().required() || field.value() != null) {
                continue;
            }
            if (isPositionField(type, field.name())) {
                continue; // checked as a pair below
            }
            if (type == MessageType.COMMAND && field.name().equals("CmdType")) {
                continue; // checked below
            }
            if (isDelete) {
                continue; // a delete only needs the id
            }
            warnings.add(new DecodeWarning(field.name(), null, "required field is empty"));
        }
        if ((type == MessageType.CONTACT || type == MessageType.POINT) && !isDelete) {
            // ICD §6.2/§6.3: exactly one of Lat/Lon or relative X/Y/Z.
            boolean absolute = message.number("Latitude").isPresent()
                    && message.number("Longitude").isPresent();
            boolean relative = message.number("relX-Distance").isPresent()
                    && message.number("relY-Distance").isPresent();
            if (!absolute && !relative) {
                warnings.add(DecodeWarning.message("neither Latitude/Longitude nor relative X/Y distances given"));
            } else if (absolute && relative) {
                warnings.add(DecodeWarning.message("both absolute and relative position given; absolute is used"));
            }
        }
        if (type == MessageType.COMMAND
                && message.field("CmdType").map(FieldValue::isEmpty).orElse(true)) {
            boolean cancelAll = message.value("CmdFlag", Integer.class).orElse(-1) == 0x03;
            if (!cancelAll) {
                warnings.add(new DecodeWarning("CmdType", null, "required unless CmdFlag is 03 (cancel all)"));
            }
        }
        if (message.text("Encoding").orElse("").equals("BASE64")) {
            String target = type == MessageType.TEXT ? "Text" : type == MessageType.GRAPHIC ? "Annotation" : "Content";
            message.text(target).ifPresent(text -> {
                if (FieldCodec.decodeBase64(text.strip()) == null) {
                    warnings.add(
                            new DecodeWarning(target, text, "Encoding is BASE64 but the value is not valid BASE64"));
                }
            });
        }
    }

    private static boolean isPositionField(MessageType type, String name) {
        return (type == MessageType.CONTACT || type == MessageType.POINT)
                && (name.equals("Latitude") || name.equals("Longitude") || name.startsWith("rel"));
    }

    /** ICD §3.3: deflate (raw, no zlib header) then BASE64. */
    private static Optional<String> tryInflate(String line) {
        if (!BASE64_LINE.matcher(line).matches()) {
            return Optional.empty();
        }
        byte[] compressed = FieldCodec.decodeBase64(line);
        if (compressed == null) {
            return Optional.empty();
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_INFLATED_BYTES) {
                    return Optional.empty();
                }
            }
            String text = out.toString(StandardCharsets.ISO_8859_1);
            int sep = text.indexOf(';');
            String name = sep < 0 ? stripTerminator(text) : text.substring(0, sep);
            return MessageType.fromWireName(name).isPresent() ? Optional.of(text) : Optional.empty();
        } catch (DataFormatException notDeflate) {
            return Optional.empty();
        } finally {
            inflater.end();
        }
    }

    private static String stripTerminator(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static String truncate(String s) {
        return s.length() > 32 ? s.substring(0, 32) + "..." : s;
    }

    private static DecodeResult rejected(String raw, String reason, boolean compressed) {
        return new DecodeResult(raw, null, List.of(DecodeWarning.message(reason)), compressed);
    }
}
