-- V33: support phone and its hours, shown on the contact page (/contact). Editable from the interpreter's account page.
INSERT INTO app_settings (key, value, type, description) VALUES
    ('brand.support_phone',    '+201096452764',                'STRING', 'Support phone number in international format (empty hides it)'),
    ('brand.support_hours.ar', 'من ٢ إلى ٦ مساءً بتوقيت مصر',   'STRING', 'When the support phone answers, in Arabic'),
    ('brand.support_hours.en', '2 PM to 6 PM, Egypt time',      'STRING', 'When the support phone answers, in English')
ON CONFLICT (key) DO NOTHING;
