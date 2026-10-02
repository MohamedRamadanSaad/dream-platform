-- V18: remember me, signed-in devices and the new sign-in e-mail (docs/SESSIONS_PROFILE_CONTRACT.md §1–3).
-- Every refresh-token family keeps its sign-in mode and its sign-in country; both are copied to each rotated token.
--   persistent = true  → "remember me": persistent cookie (Max-Age), lifetime auth.refresh_ttl_days
--   persistent = false → browser-session cookie (no Max-Age), lifetime auth.session_ttl_hours
-- Tokens issued before this change were written with a persistent cookie, hence the default.
ALTER TABLE refresh_tokens ADD COLUMN persistent boolean NOT NULL DEFAULT true;
ALTER TABLE refresh_tokens ADD COLUMN country_code char(2);

INSERT INTO app_settings (key, value, type, description) VALUES
    ('auth.session_ttl_hours', '12', 'INT', 'Sign-in without "remember me": the session ends after N hours (and when the browser is closed)'),
    ('auth.known_device_days', '90', 'INT', 'New sign-in e-mail: a browser + system used to sign in during the last N days is a known device'),
    ('mail.event.new-sign-in', 'true', 'BOOL', 'E-mail the user when the account is signed in from a new device'),
    ('mail.theme.new-sign-in', '', 'STRING', 'E-mail theme of new-sign-in; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;

-- "remember me" now lasts 90 days instead of 30 — only where the value was never changed from the dashboard
UPDATE app_settings
SET value = '90', updated_at = now()
WHERE key = 'auth.refresh_ttl_days' AND value = '30';

UPDATE app_settings
SET description = 'Sign-in with "remember me": the session lasts N days, renewed on every visit'
WHERE key = 'auth.refresh_ttl_days';
