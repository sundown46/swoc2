package io.swoc2.sedap.codec;

import java.nio.charset.Charset;
import java.util.Base64;

/**
 * A BASE64 field value (ICD §2), kept in its encoded form so it can be re-sent unchanged and
 * decoded only when needed. Only constructed for input that decodes successfully.
 */
public record Base64Data(String encoded) {

    /** Decoded bytes. */
    public byte[] bytes() {
        return Base64.getDecoder().decode(encoded);
    }

    /** Decoded text. The ICD says "ASCII = ISO-8859-1" (§1); pass a charset for UTF-8 senders. */
    public String text(Charset charset) {
        return new String(bytes(), charset);
    }

    public static Base64Data of(byte[] bytes) {
        return new Base64Data(Base64.getEncoder().encodeToString(bytes));
    }
}
