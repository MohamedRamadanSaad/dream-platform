-- V1: business settings (read through SettingsService; keys in com.saadat.settings.SettingKeys)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE app_settings (
    key         varchar(100) PRIMARY KEY,
    value       text,
    type        varchar(16)  NOT NULL CHECK (type IN ('STRING', 'INT', 'BOOL', 'JSON')),
    description text,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid
);

INSERT INTO app_settings (key, value, type, description) VALUES
    -- brand
    ('brand.name.ar',        'إلى سعادة الدارين', 'STRING', 'Brand name (Arabic)'),
    ('brand.name.en',        'Saadat Al-Darain', 'STRING', 'Brand name (English)'),
    ('brand.tagline.ar',     'تفسير الرؤى بوضوح وعمق نحو سعادة الدارين', 'STRING', 'Tagline (Arabic)'),
    ('brand.tagline.en',     'Dream interpretation with clarity and depth', 'STRING', 'Tagline (English)'),
    ('brand.support_email',  'support@saadatu-aldarein.com', 'STRING', 'Support e-mail shown in e-mails and footer'),
    ('brand.youtube_url',    'https://youtube.com/@almoaberafatema', 'STRING', 'YouTube channel URL'),
    ('brand.youtube_channel_id', '', 'STRING', 'YouTube channel id (UC...) for the RSS poller; empty disables polling'),
    -- wait time
    ('wait.busy',            'false', 'BOOL', 'Busy mode: SLA = busy_max_days*24 instead of normal_hours'),
    ('wait.normal_hours',    '48', 'INT', 'Normal SLA in hours'),
    ('wait.busy_min_days',   '2', 'INT', 'Busy mode: minimum days shown to users'),
    ('wait.busy_max_days',   '3', 'INT', 'Busy mode: maximum days (used as SLA)'),
    ('wait.message_ar',      'نظراً لكثرة الرؤى، يستغرق التعبير حالياً من يومين إلى ثلاثة أيام، لنمنح كل رؤيا حقها من الدراسة المتعمقة وتحليل الرموز.', 'STRING', 'Wait message (Arabic)'),
    ('wait.message_en',      'Due to high demand, interpretations currently take two to three days so every dream gets the deep study it deserves.', 'STRING', 'Wait message (English)'),
    ('wait.auto_reset_at',   NULL, 'STRING', 'ISO-8601 instant when busy mode switches off automatically (null = never)'),
    -- dreams
    ('dreams.min_chars',     '20', 'INT', 'Minimum dream text length'),
    ('dreams.max_chars',     '4000', 'INT', 'Maximum dream text length'),
    ('dreams.draft_limit',   '20', 'INT', 'Maximum number of drafts per user'),
    ('dreams.reply_reminder_hours', '48', 'INT', 'Remind the user after N hours in AWAITING_USER_REPLY'),
    ('dreams.testimonial_request_days', '7', 'INT', 'Ask for a testimonial N days after interpretation'),
    -- auth
    ('auth.magic_ttl_minutes',  '15', 'INT', 'Magic link / code validity in minutes'),
    ('auth.access_ttl_minutes', '15', 'INT', 'Access token TTL in minutes'),
    ('auth.refresh_ttl_days',   '30', 'INT', 'Refresh token TTL in days'),
    -- orders
    ('orders.expire_minutes', '30', 'INT', 'INITIATED orders expire after N minutes'),
    -- interpreter
    ('interpreter.emails',      'fatema@saadatu-aldarein.com', 'STRING', 'Comma-separated e-mails that become INTERPRETER on first login'),
    ('interpreter.digest_hour', '9', 'INT', 'Hour of the daily interpreter digest (schedule.time_zone)'),
    -- pricing
    ('pricing.global_currency', 'USD', 'STRING', 'Currency of GLOBAL price rules / analytics base currency'),
    ('pricing.default_country', 'SA', 'STRING', 'Fallback country when CF-IPCountry is absent'),
    ('pricing.fx_to_usd',       '{"EGP":0.0208,"SAR":0.2667,"USD":1}', 'JSON', 'Static FX rates to USD for analytics'),
    -- youtube
    ('youtube.poll_minutes', '30', 'INT', 'YouTube RSS poll interval in minutes'),
    -- public stats
    ('stats.subscribers', '50K+', 'STRING', 'Public stat: subscribers (display string)'),
    ('stats.views',       '1M+',  'STRING', 'Public stat: views (display string)'),
    ('stats.videos',      '230+', 'STRING', 'Public stat: videos (display string)'),
    -- scheduling
    ('schedule.time_zone', 'Africa/Cairo', 'STRING', 'IANA time zone for daily schedules');
