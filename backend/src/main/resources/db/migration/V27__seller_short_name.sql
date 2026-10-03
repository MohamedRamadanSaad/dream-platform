-- V27: the provider name shown on the terms page, footer and receipt is the short name (owner's decision).
-- Only replaces the value seeded by V22, so a name edited from the dashboard is left alone.
UPDATE app_settings SET value = 'فاطمه عبدالوهاب', updated_at = now()
WHERE key = 'brand.legal_name' AND value = 'فاطمه عبدالوهاب محمد عبوالوهاب';
