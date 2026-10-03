/**
 * Canonical contact model (ARCHITECTURE §5, ADR 0008): protocol-independent, SI units and UTC
 * only. Adapters (SEDAP-Express, AIS, ...) map onto it; the picture store and every client work
 * with it.
 */
package io.swoc2.domain.picture;
