/**
 * Canonical domain model (contacts, units, geodesy, MGRS) shared by the app and plugins.
 *
 * <p>No Spring dependency on purpose (ARCHITECTURE §3): this module must stay usable from plain
 * tools and from both backend plugin kinds. SEDAP-Express and other source formats are adapters
 * onto this model (decision D-007); the model itself never speaks a wire format.
 */
package io.swoc2.domain;
