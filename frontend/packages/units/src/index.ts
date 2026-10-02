/**
 * Unit and coordinate formatting/parsing shared by the app and plugins (ARCHITECTURE §5.3,
 * §6). The domain only ever uses SI units and UTC; conversion for display happens here, never
 * inline (CLAUDE.md non-negotiable principle #5). Covers distance/speed/altitude units,
 * bearings, and coordinate formats (decimal degrees, deg-dec-min, deg-min-sec, MGRS).
 *
 * Placeholder: implemented with extensive unit tests (incl. MGRS and messy pasted input) as
 * part of MAP-016/017 (P1). Not part of this repo-skeleton change.
 */
export {};
