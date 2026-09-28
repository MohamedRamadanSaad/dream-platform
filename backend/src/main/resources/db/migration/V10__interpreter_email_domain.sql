-- Any e-mail on the site domain is an interpreter account: it must sign in with the e-mail code (no Google).
INSERT INTO app_settings (key, value, type, description) VALUES
    ('interpreter.email_domain', 'saadatu-aldarein.com', 'STRING',
     'E-mails ending with @<domain> get role INTERPRETER and may only sign in via magic link/code (empty disables)')
ON CONFLICT (key) DO NOTHING;
