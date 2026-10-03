-- V21: support tickets — one row per e-mail a PERSON sent to the support mailbox (POST /webhooks/mail/inbound after
-- the loop/automation guards). The message body is never stored: only sender, subject and when it was received.
-- support_tickets.ticket_number = human number shown as #1001, #1002 … (sequence).
-- support_tickets.message_id = Message-ID of the incoming e-mail; unique so a repeated webhook delivery is one ticket,
--   and used as In-Reply-To / References of our replies (same thread in the sender's mail app).
-- support_ticket_events: the interpreter's messages (IN_PROGRESS / CLOSED), e-mailed to the sender; email_status =
--   outcome of that e-mail (SENT / LOGGED / FAILED / DISABLED).
CREATE SEQUENCE support_ticket_number_seq START WITH 1001 INCREMENT BY 1;

CREATE TABLE support_tickets (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number   bigint        NOT NULL DEFAULT nextval('support_ticket_number_seq'),
    from_email      varchar(320)  NOT NULL,
    from_name       varchar(200),
    subject         varchar(500),
    message_id      varchar(500),
    received_at     timestamptz   NOT NULL,
    status          varchar(20)   NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'IN_PROGRESS', 'CLOSED')),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    last_action_by  uuid          REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ux_support_tickets_number UNIQUE (ticket_number),
    CONSTRAINT ux_support_tickets_message_id UNIQUE (message_id)
);
ALTER SEQUENCE support_ticket_number_seq OWNED BY support_tickets.ticket_number;
CREATE INDEX ix_support_tickets_status_received ON support_tickets (status, received_at DESC);
CREATE INDEX ix_support_tickets_sender_created ON support_tickets (from_email, created_at DESC);

CREATE TABLE support_ticket_events (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id     uuid         NOT NULL REFERENCES support_tickets (id) ON DELETE CASCADE,
    action        varchar(20)  NOT NULL CHECK (action IN ('IN_PROGRESS', 'CLOSED')),
    message       text         NOT NULL,
    actor_id      uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    email_status  varchar(20)
);
CREATE INDEX ix_support_ticket_events_ticket_created ON support_ticket_events (ticket_id, created_at DESC);

INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.event.support-in-progress', 'true', 'BOOL', 'E-mail the sender of a support message when the interpreter starts working on it (with her message)'),
    ('mail.event.support-closed', 'true', 'BOOL', 'E-mail the sender of a support message when the interpreter closes the ticket (with her message)'),
    ('mail.theme.support-in-progress', '', 'STRING', 'E-mail theme of support-in-progress; empty = mail.theme.default'),
    ('mail.theme.support-closed', '', 'STRING', 'E-mail theme of support-closed; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;
