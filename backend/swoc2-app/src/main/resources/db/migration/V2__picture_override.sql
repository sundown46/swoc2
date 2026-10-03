-- V2: operator overrides of contact attributes (PIC-003, ARCHITECTURE §5.2, §10).
-- Keyed by the contact's source key, so edits survive source updates and restarts.
CREATE TABLE picture_override (
    connection_id    TEXT        NOT NULL,
    source_system_id TEXT        NOT NULL,
    source_track_id  TEXT        NOT NULL,
    fields           JSONB       NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       TEXT        NOT NULL,
    PRIMARY KEY (connection_id, source_system_id, source_track_id)
);
