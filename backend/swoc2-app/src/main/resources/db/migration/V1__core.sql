-- V1: extensions, audit log, instance settings (P1 M1; ARCHITECTURE §10, ADR 0020).
-- The database user needs the right to create extensions (or an admin creates them beforehand,
-- see deploy/README.md). Both ship with the timescale/timescaledb-ha image used everywhere.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Audit log (AUTH-005, ADM-006, CLAUDE.md principle 10): who, what, when, before/after.
-- Append-only: UPDATE and DELETE are rejected by a trigger, not just by convention.
CREATE TABLE audit_event (
    id           BIGSERIAL PRIMARY KEY,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor        TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    target_type  TEXT,
    target_id    TEXT,
    before_state JSONB,
    after_state  JSONB,
    details      JSONB,
    client_ip    TEXT
);
CREATE INDEX audit_event_occurred_at_idx ON audit_event (occurred_at DESC);
CREATE INDEX audit_event_actor_idx ON audit_event (actor, occurred_at DESC);
CREATE INDEX audit_event_action_idx ON audit_event (action, occurred_at DESC);

CREATE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

-- Instance settings (ADM-003, SDX-004): one JSON value per key, validated by the application.
CREATE TABLE instance_setting (
    key        TEXT PRIMARY KEY,
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT        NOT NULL
);
