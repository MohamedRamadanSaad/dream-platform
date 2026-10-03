-- V25: keep a copy of every sent e-mail in the mailbox's Sent folder (IMAP APPEND after SMTP delivery).
-- Sign-in e-mails (magic-link) are never copied: their one-time links must not sit in a shared mailbox.
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.save_to_sent', 'true', 'BOOL', 'Keep a copy of each sent e-mail in the mailbox Sent folder (sign-in e-mails excluded)'),
    ('mail.sent_folder', 'INBOX.Sent', 'STRING', 'IMAP folder for the copies of sent e-mails (Hostinger: INBOX.Sent)')
ON CONFLICT (key) DO NOTHING;
