/**
 * Tolerant SEDAP-Express codec (SDX-001, ARCHITECTURE §11.1, ADR 0017): {@link
 * io.swoc2.sedap.codec.SedapDecoder} turns one wire line into a {@link
 * io.swoc2.sedap.codec.SedapMessage} plus warnings and never throws; {@link
 * io.swoc2.sedap.codec.SedapEncoder} and {@link io.swoc2.sedap.codec.SedapMessageBuilder}
 * generate lines. Both are driven by the schemas in {@code io.swoc2.sedap.schema}.
 *
 * <p>Tolerance rules: an invalid field value is kept raw (value {@code null}) with a warning; a
 * missing required field is a warning; unknown trailing fields are kept; an unknown message name
 * is reported, not thrown. The decoder never interprets semantics beyond the ICD's types and
 * ranges - unit conversion and normalisation belong to the domain mapping.
 */
package io.swoc2.sedap.codec;
