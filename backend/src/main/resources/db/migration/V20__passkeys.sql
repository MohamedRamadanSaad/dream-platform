-- V20: passkeys — sign-in with fingerprint / face / device PIN (WebAuthn, docs/PASSKEYS_CONTRACT.md).
-- passkeys: one row per WebAuthn credential of an account (the private key never leaves the user's device).
--   credential_id = rawId, public_key = COSE_Key (CBOR), sign_count = highest signature counter seen,
--   aaguid = authenticator model (all zeros for most passkeys), transports = "internal,hybrid" as reported.
-- passkey_challenges: the random challenge of each registration / sign-in ceremony, single use and short-lived
--   (auth.passkey_challenge_ttl_seconds); a registration challenge belongs to the signed-in user.
CREATE TABLE passkeys (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    credential_id  bytea        NOT NULL,
    public_key     bytea        NOT NULL,
    sign_count     bigint       NOT NULL DEFAULT 0 CHECK (sign_count >= 0),
    aaguid         uuid,
    transports     varchar(200),
    label          varchar(100) NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    last_used_at   timestamptz,
    CONSTRAINT ux_passkeys_credential_id UNIQUE (credential_id)
);
CREATE INDEX ix_passkeys_user_created ON passkeys (user_id, created_at DESC);

CREATE TABLE passkey_challenges (
    request_id  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         REFERENCES users (id) ON DELETE CASCADE,
    purpose     varchar(16)  NOT NULL CHECK (purpose IN ('REGISTRATION', 'AUTHENTICATION')),
    challenge   bytea        NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz,
    CONSTRAINT ck_passkey_challenges_owner CHECK (purpose = 'AUTHENTICATION' OR user_id IS NOT NULL)
);
CREATE INDEX ix_passkey_challenges_expires ON passkey_challenges (expires_at);

INSERT INTO app_settings (key, value, type, description) VALUES
    ('auth.passkey_challenge_ttl_seconds', '300', 'INT', 'Passkeys: a fingerprint / face sign-in or "add passkey" request must be completed within N seconds'),
    ('mail.event.passkey-added', 'true', 'BOOL', 'E-mail the account owner when a passkey (fingerprint / face sign-in) is added'),
    ('mail.theme.passkey-added', '', 'STRING', 'E-mail theme of passkey-added; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;
