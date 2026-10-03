-- V28: new-video e-mails go to every user (owner's decision). users.marketing_opt_in now means "channel e-mails on":
-- on for everyone (and for new accounts), off only when the user stops them (link in the e-mail, or My account).
-- youtube.mail_daily_limit caps these e-mails per 24 hours so the shared mailbox quota stays free for sign-in codes,
-- receipts and dream e-mails; the rest go out over the following hours (YoutubeMailBacklogJob).
UPDATE users SET marketing_opt_in = true WHERE deleted_at IS NULL AND role = 'USER';

INSERT INTO app_settings (key, value, type, description) VALUES
    ('youtube.mail_daily_limit', '300', 'INT', 'Most new-video e-mails sent in any 24 hours (the rest wait)')
ON CONFLICT (key) DO NOTHING;
