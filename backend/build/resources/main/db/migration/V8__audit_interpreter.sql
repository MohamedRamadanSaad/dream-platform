-- V8: audit log + interpreter notes about users
-- (interpreter settings such as busy mode live in app_settings, not in a table)

CREATE TABLE audit_log (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    uuid,
    action      varchar(64)  NOT NULL,
    entity      varchar(64)  NOT NULL,
    entity_id   varchar(128),
    before      jsonb,
    after       jsonb,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_entity ON audit_log (entity, entity_id);
CREATE INDEX ix_audit_log_created ON audit_log (created_at DESC);

CREATE TABLE user_notes (
    user_id     uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    notes       text        NOT NULL DEFAULT '',
    tags        text[]      NOT NULL DEFAULT '{}',
    updated_at  timestamptz NOT NULL DEFAULT now()
);
