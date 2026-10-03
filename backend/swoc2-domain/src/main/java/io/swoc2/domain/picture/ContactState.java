package io.swoc2.domain.picture;

/** LIVE until not updated for the stale time, then STALE (MAP-019), then removed. */
public enum ContactState {
    LIVE,
    STALE
}
