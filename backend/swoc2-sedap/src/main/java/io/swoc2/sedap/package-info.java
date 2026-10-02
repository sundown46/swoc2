/**
 * SEDAP-Express support (ARCHITECTURE §3, §11; ICD in {@code docs/icd/}).
 *
 * <ul>
 *   <li>{@code schema}: declarative description of every ICD message type, including the
 *       COMMAND schema the tasking wizard is generated from
 *   <li>{@code codec}: tolerant decoder and strict builder/encoder (SDX-001)
 * </ul>
 *
 * Own implementation instead of the reference library, which is a test-only conformance oracle;
 * see ADR 0017 for why. Mapping to the domain model (SDX-005) follows in P1.
 */
package io.swoc2.sedap;
