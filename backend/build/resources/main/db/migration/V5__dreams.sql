-- V5: dreams, messages, interpretations, testimonials

CREATE TABLE dreams (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid        NOT NULL REFERENCES users (id),
    gender              varchar(16) NOT NULL CHECK (gender IN ('FEMALE', 'MALE')),
    status              varchar(24) NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT', 'IN_REVIEW', 'AWAITING_USER_REPLY', 'INTERPRETED', 'CANCELLED')),
    text                text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    submitted_at        timestamptz,
    sla_hours_snapshot  integer,
    expected_by         timestamptz,
    sla_paused_at       timestamptz,
    interpreted_at      timestamptz,
    cancelled_reason    text,
    ledger_entry_id     uuid        REFERENCES credit_ledger (id),
    assignee_id         uuid        REFERENCES users (id)
);
CREATE INDEX ix_dreams_user_created ON dreams (user_id, created_at DESC);
CREATE INDEX ix_dreams_status_submitted ON dreams (status, submitted_at);

ALTER TABLE credit_ledger
    ADD CONSTRAINT fk_credit_ledger_dream FOREIGN KEY (dream_id) REFERENCES dreams (id) ON DELETE SET NULL;
-- Idempotency guards: one SUBMIT debit and one REFUND credit per dream.
CREATE UNIQUE INDEX ux_credit_ledger_submit_dream ON credit_ledger (dream_id) WHERE reason = 'SUBMIT';
CREATE UNIQUE INDEX ux_credit_ledger_refund_dream ON credit_ledger (dream_id) WHERE reason = 'REFUND';

CREATE TABLE dream_messages (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dream_id     uuid        NOT NULL REFERENCES dreams (id) ON DELETE CASCADE,
    sender_role  varchar(16) NOT NULL CHECK (sender_role IN ('USER', 'INTERPRETER')),
    body         text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    read_at      timestamptz
);
CREATE INDEX ix_dream_messages_dream_created ON dream_messages (dream_id, created_at);

CREATE TABLE interpretations (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dream_id        uuid        NOT NULL REFERENCES dreams (id) ON DELETE CASCADE,
    interpreter_id  uuid        NOT NULL REFERENCES users (id),
    text            text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_interpretations_dream UNIQUE (dream_id)
);

CREATE TABLE testimonials (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dream_id    uuid        NOT NULL REFERENCES dreams (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rating      integer     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     text        NOT NULL DEFAULT '',
    approved    boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_testimonials_dream UNIQUE (dream_id)
);
CREATE INDEX ix_testimonials_approved_created ON testimonials (approved, created_at DESC);
CREATE INDEX ix_testimonials_user ON testimonials (user_id);
