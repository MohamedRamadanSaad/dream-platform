-- V2: users & authentication

CREATE TABLE users (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email             varchar(320) NOT NULL,
    name              varchar(200) NOT NULL DEFAULT '',
    gender            varchar(16)  CHECK (gender IN ('FEMALE', 'MALE')),
    role              varchar(16)  NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'INTERPRETER')),
    locale            varchar(8)   NOT NULL DEFAULT 'AR' CHECK (locale IN ('AR', 'EN')),
    country_code      char(2),
    country_source    varchar(16)  CHECK (country_source IN ('IP', 'HEADER', 'DEFAULT', 'ADMIN')),
    onboarded         boolean      NOT NULL DEFAULT false,
    marketing_opt_in  boolean      NOT NULL DEFAULT false,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    last_login_at     timestamptz,
    deleted_at        timestamptz,
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email))
);
CREATE UNIQUE INDEX ux_users_email ON users (email);
CREATE INDEX ix_users_country ON users (country_code);
CREATE INDEX ix_users_created ON users (created_at);

CREATE TABLE auth_identities (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider          varchar(16)  NOT NULL CHECK (provider IN ('GOOGLE', 'MAGIC_LINK')),
    provider_subject  varchar(255) NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    last_used_at      timestamptz,
    CONSTRAINT ux_auth_identities_provider_subject UNIQUE (provider, provider_subject)
);
CREATE INDEX ix_auth_identities_user ON auth_identities (user_id);

CREATE TABLE magic_links (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       varchar(320) NOT NULL,
    token_hash  varchar(128) NOT NULL,
    code_hash   varchar(128) NOT NULL,
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz,
    created_ip  varchar(64),
    attempts    integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_magic_links_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_magic_links_email_created ON magic_links (email, created_at DESC);
CREATE INDEX ix_magic_links_expires ON magic_links (expires_at);

CREATE TABLE refresh_tokens (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  varchar(128) NOT NULL,
    family_id   uuid         NOT NULL,
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz,
    user_agent  varchar(512),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_tokens_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens (expires_at);

CREATE TABLE user_sessions (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid         REFERENCES users (id) ON DELETE CASCADE,
    country_code  char(2),
    user_agent    varchar(512),
    started_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_sessions_user_started ON user_sessions (user_id, started_at DESC);
CREATE INDEX ix_user_sessions_country_started ON user_sessions (country_code, started_at);
