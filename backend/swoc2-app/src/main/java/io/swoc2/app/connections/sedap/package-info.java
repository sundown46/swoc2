/**
 * SEDAP-Express ingest (ARCHITECTURE §4, §11.3; SDX-005): decode -> loop prevention (own sender,
 * dedup) -> map to the canonical model -> live picture. Never throws: a bad frame is counted,
 * shown in the debug console and dropped.
 */
package io.swoc2.app.connections.sedap;
