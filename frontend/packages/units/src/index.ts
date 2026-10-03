/**
 * Unit and coordinate formatting/parsing shared by the app and plugins (ARCHITECTURE §5.3). The
 * domain only ever uses SI units and UTC; conversion for display happens here, never inline
 * (CLAUDE.md principle 5). Geodesy (GeographicLib) and MGRS match the backend's libraries.
 */
export * from './angles';
export * from './coords';
export * from './geodesy';
export * from './units';
