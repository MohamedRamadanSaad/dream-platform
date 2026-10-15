-- V37: e-mail + password accounts (owner's decision of 2026-10-15; replaces the old "no passwords" rule).
-- Sign-up and "forgot password" prove the e-mail with a 6-digit code (magic_links rows with a purpose); a verified
-- code gives a short single-use ticket (stored hashed) that the next step exchanges for the password change.

-- users: BCrypt hash (null = no password yet), when the terms were accepted, and the wrong-password lockout
ALTER TABLE users ADD COLUMN password_hash            varchar(100);
ALTER TABLE users ADD COLUMN password_set_at          timestamptz;
ALTER TABLE users ADD COLUMN terms_accepted_at        timestamptz;
ALTER TABLE users ADD COLUMN failed_password_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN password_locked_until    timestamptz;

-- people who finished onboarding already accepted the terms there
UPDATE users SET terms_accepted_at = created_at WHERE onboarded AND terms_accepted_at IS NULL;

-- auth_identities.provider gains PASSWORD (the V2 CHECK was declared inline: PostgreSQL chose its name, so it is
-- dropped by its definition)
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'auth_identities' AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%MAGIC_LINK%'
    LOOP
        EXECUTE format('ALTER TABLE auth_identities DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE auth_identities ADD CONSTRAINT auth_identities_provider_check
    CHECK (provider IN ('GOOGLE', 'MAGIC_LINK', 'PASSWORD'));

-- magic_links: what the code is for, and the ticket a verified SIGN_UP / RESET_PASSWORD code gives
ALTER TABLE magic_links ADD COLUMN purpose varchar(16) NOT NULL DEFAULT 'SIGN_IN';
ALTER TABLE magic_links ADD CONSTRAINT ck_magic_links_purpose
    CHECK (purpose IN ('SIGN_IN', 'SIGN_UP', 'RESET_PASSWORD'));
ALTER TABLE magic_links ADD COLUMN ticket_hash       varchar(128);
ALTER TABLE magic_links ADD COLUMN ticket_expires_at timestamptz;
ALTER TABLE magic_links ADD COLUMN ticket_used_at    timestamptz;
CREATE UNIQUE INDEX ux_magic_links_ticket_hash ON magic_links (ticket_hash) WHERE ticket_hash IS NOT NULL;
CREATE INDEX ix_magic_links_email_purpose_created ON magic_links (email, purpose, created_at DESC);

-- the code e-mail (sign-up / reset password) has its theme like every template; like magic-link it has no switch
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.theme.email-code', '', 'STRING', 'E-mail theme of email-code; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;
