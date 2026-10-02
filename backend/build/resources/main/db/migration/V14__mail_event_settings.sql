-- V14: one BOOL switch per e-mail event (mail.event.<template>), editable from /admin/settings.
-- magic-link has no switch: sign-in e-mails are always sent.
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.event.welcome',              'true', 'BOOL', 'E-mail the user a welcome message after onboarding'),
    ('mail.event.payment-failed',       'true', 'BOOL', 'E-mail the user when a payment was not successful'),
    ('mail.event.dream-cancelled',      'true', 'BOOL', 'E-mail the user when a dream is cancelled (credit refunded)'),
    ('mail.event.credits-adjusted',     'true', 'BOOL', 'E-mail the user after a manual credit change'),
    ('mail.event.testimonial-approved', 'true', 'BOOL', 'E-mail the user when the testimonial is approved'),
    ('mail.event.account-deleted',      'true', 'BOOL', 'E-mail the user when the account is deleted'),
    ('mail.event.new-user',             'true', 'BOOL', 'E-mail the interpreter when a new user finishes onboarding'),
    ('mail.event.testimonial-received', 'true', 'BOOL', 'E-mail the interpreter when a testimonial awaits approval'),
    ('mail.event.dream-submitted',      'true', 'BOOL', 'E-mail the interpreter when a dream is submitted'),
    ('mail.event.dream-received',       'true', 'BOOL', 'E-mail the user when the dream is received'),
    ('mail.event.interpreter-question', 'true', 'BOOL', 'E-mail the user when the interpreter asks a question'),
    ('mail.event.user-replied',         'true', 'BOOL', 'E-mail the interpreter when the user replies'),
    ('mail.event.interpretation-ready', 'true', 'BOOL', 'E-mail the user when the interpretation is ready'),
    ('mail.event.payment-receipt',      'true', 'BOOL', 'E-mail the user a receipt after a successful payment'),
    ('mail.event.payment-suspicious',   'true', 'BOOL', 'E-mail the interpreter about a payment that needs review'),
    ('mail.event.reply-reminder',       'true', 'BOOL', 'Remind the user to answer the interpreter''s question'),
    ('mail.event.testimonial-request',  'true', 'BOOL', 'Ask the user for a testimonial after the interpretation'),
    ('mail.event.interpreter-digest',   'true', 'BOOL', 'Daily digest e-mail to the interpreter'),
    ('mail.event.youtube-new-video',    'true', 'BOOL', 'E-mail opted-in users when a new YouTube video is published')
ON CONFLICT (key) DO NOTHING;
