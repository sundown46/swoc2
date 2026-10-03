package io.swoc2.app.picture;

/**
 * Receives picture changes synchronously, on the thread that made the change. Must be fast and
 * must not throw (exceptions are caught and logged, but cost time on the ingest path): heavy work
 * belongs on the listener's own queue (e.g. realtime batching).
 */
@FunctionalInterface
public interface PictureListener {
    void onChange(PictureChange change);
}
