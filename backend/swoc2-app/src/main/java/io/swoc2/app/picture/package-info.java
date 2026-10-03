/**
 * Live picture (PIC-001..005, ARCHITECTURE §4-§5, §10): the shared, server-authoritative contact
 * store, held in memory with an MGRS 100 km cell index, aging (stale/delete), the persisted
 * operator override layer, and wipe. Connections feed it via {@link
 * io.swoc2.app.picture.PictureStore#upsert}; realtime and other consumers listen via {@link
 * io.swoc2.app.picture.PictureListener}.
 */
package io.swoc2.app.picture;
