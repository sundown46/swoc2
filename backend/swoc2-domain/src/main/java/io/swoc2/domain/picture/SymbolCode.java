package io.swoc2.domain.picture;

/**
 * A symbol reference: which symbol set (provider id, e.g. {@code 2525c}) and the code within it
 * (ARCHITECTURE §5.1, §9). Rendering is the frontend's job.
 */
public record SymbolCode(String set, String code) {

    public static final String APP6_LETTER = "2525c";

    public SymbolCode {
        if (set == null || set.isBlank() || code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("Invalid symbol code");
        }
    }

    /** Letter SIDC (15 chars), as used by SEDAP-Express (ICD §1). */
    public static SymbolCode sidc(String code) {
        return new SymbolCode(APP6_LETTER, code.toUpperCase(java.util.Locale.ROOT));
    }
}
