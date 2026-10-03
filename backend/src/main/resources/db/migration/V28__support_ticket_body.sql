-- V28: the owner now wants to read what the person wrote directly on the support page (privacy change: until V26
-- the message body was never stored). support_tickets.body = plain text of the incoming e-mail (normalized line
-- endings, trimmed, quoted reply cut, at most 20,000 characters); NULL until it is known — taken from the inbound
-- webhook payload when present, otherwise fetched read-only over IMAP from INBOX by Message-ID.
-- support_tickets.body_fetched_at = when the text was last looked for (successful or not), so the backfill job
-- retries at most once per hour.
ALTER TABLE support_tickets ADD COLUMN body text;
ALTER TABLE support_tickets ADD COLUMN body_fetched_at timestamptz;
CREATE INDEX ix_support_tickets_body_missing ON support_tickets (received_at DESC) WHERE body IS NULL;

INSERT INTO app_settings (key, value, type, description) VALUES
    ('support.fetch_body', 'true', 'BOOL', 'Read the text of support e-mails from the mailbox (IMAP, read-only) when the webhook does not include it')
ON CONFLICT (key) DO NOTHING;
