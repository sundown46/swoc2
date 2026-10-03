package io.swoc2.sedap.schema;

/**
 * Wire type of a single SEDAP-Express field. Each kind defines how the raw CSV text is parsed and
 * how a value is formatted back ({@code io.swoc2.sedap.codec.FieldCodec}).
 */
public enum FieldKind {
    /** Free ASCII text (ICD §2: max 256 bytes unless the field says otherwise). */
    TEXT,
    /** Floating-point number, plain decimal notation, '.' as decimal separator. */
    DOUBLE,
    /** Latitude in decimal degrees, [-90, 90]. */
    LATITUDE,
    /** Longitude in decimal degrees, [-180, 180]. */
    LONGITUDE,
    /** Angle in degrees relative to true north, nominally [0, 360) (ICD §2). */
    ANGLE,
    /** Integer number (decimal). */
    INTEGER,
    /** Hex-coded code from a code table ({@link FieldSpec#codes()}), e.g. {@code 04} = Chat. */
    CODE,
    /** Hex string of arbitrary length (MAC, CmdID, key material, ...). */
    HEX,
    /** 64-bit Unix time in milliseconds, written as hex (ICD §2). */
    HEX_TIME,
    /** {@code TRUE} / {@code FALSE}. */
    BOOLEAN,
    /** {@code ON} / {@code OFF}. */
    ON_OFF,
    /** {@code BASE64} / {@code NONE} encoding indicator. */
    ENCODING,
    /** BASE64-encoded binary or text. */
    BASE64,
    /** 15-character symbol identification code (APP-6 / MIL-STD-2525, ICD §1). */
    SIDC,
    /** One or more source characters (R, A, I, S, E, O, Y, M; ICD §6.2). */
    SOURCE_CHARS,
    /** {@code #}-separated list of numbers (e.g. EMISSION frequencies). */
    DOUBLE_LIST,
    /** {@code #}-separated {@code name#percent#name#percent...} pairs (STATUS levels). */
    LEVEL_LIST,
    /** One {@code lat,lon[,alt]} coordinate. */
    COORDINATE,
    /** {@code #}-separated list of {@code lat,lon[,alt]} coordinates (GRAPHIC paths). */
    COORDINATE_LIST,
    /** {@code #}-separated list of free text values. */
    TEXT_LIST
}
