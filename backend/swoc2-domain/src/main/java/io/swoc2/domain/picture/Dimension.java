package io.swoc2.domain.picture;

/** Battle dimension (APP-6), 3rd character of a letter SIDC. */
public enum Dimension {
    SPACE,
    AIR,
    GROUND,
    SEA_SURFACE,
    SUBSURFACE,
    SOF,
    OTHER,
    UNKNOWN;

    public static Dimension fromSidc(String sidc) {
        if (sidc == null || sidc.length() < 3) {
            return UNKNOWN;
        }
        return switch (Character.toUpperCase(sidc.charAt(2))) {
            case 'P' -> SPACE;
            case 'A' -> AIR;
            case 'G' -> GROUND;
            case 'S' -> SEA_SURFACE;
            case 'U' -> SUBSURFACE;
            case 'F' -> SOF;
            case 'X' -> OTHER;
            default -> UNKNOWN;
        };
    }
}
