-- V3: configured connections (CON-001, ARCHITECTURE §11.2). Secrets inside `config` (fields marked
-- writeOnly in the type's schema) are never returned by the API; encryption at rest comes with the
-- first connection type that has secrets (MQTT, M3b).
CREATE TABLE connection (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL UNIQUE,
    type       TEXT        NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    direction  TEXT        NOT NULL,
    config     JSONB       NOT NULL,
    aging      JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT        NOT NULL
);
