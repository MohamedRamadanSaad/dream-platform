-- V6: in-app notifications, web push subscriptions, e-mail log

CREATE TABLE notifications (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        varchar(32)  NOT NULL CHECK (type IN ('DREAM_SUBMITTED', 'DREAM_RECEIVED', 'INTERPRETER_QUESTION',
                                                     'USER_REPLIED', 'INTERPRETATION_READY', 'PAYMENT_SUCCESS',
                                                     'PROMOTION', 'YOUTUBE_VIDEO')),
    title       varchar(300) NOT NULL,
    body        text         NOT NULL,
    link        varchar(500),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    read_at     timestamptz
);
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE push_subscriptions (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint         text         NOT NULL,
    p256dh           varchar(255) NOT NULL,
    auth             varchar(255) NOT NULL,
    user_agent       varchar(512),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    last_success_at  timestamptz,
    failures         integer      NOT NULL DEFAULT 0,
    CONSTRAINT ux_push_subscriptions_endpoint UNIQUE (endpoint)
);
CREATE INDEX ix_push_subscriptions_user ON push_subscriptions (user_id);

CREATE TABLE email_log (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid         REFERENCES users (id) ON DELETE SET NULL,
    to_email     varchar(320) NOT NULL,
    template     varchar(64)  NOT NULL,
    ref          varchar(128), -- correlation key (e.g. dream id) for send-once e-mails
    subject      varchar(500),
    status       varchar(16)  NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'LOGGED')),
    provider_id  varchar(255),
    error        text,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_log_template_ref ON email_log (template, ref);
CREATE INDEX ix_email_log_created ON email_log (created_at DESC);
