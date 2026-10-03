-- V21: seller details shown on the terms page, in the footer and on the payment receipt
-- (Egyptian consumer protection law 181/2018, distance selling). Editable from the interpreter's account page.
INSERT INTO app_settings (key, value, type, description) VALUES
    ('brand.legal_name',          'فاطمه عبدالوهاب محمد عبوالوهاب', 'STRING', 'Seller legal name exactly as on the tax card (empty hides it)'),
    ('brand.legal_address',       'دمياط الجديدة، محافظة دمياط', 'STRING', 'Seller address or governorate (empty hides it)'),
    ('brand.tax_registration_no', '114-683-768', 'STRING', 'Tax registration number (empty hides it)')
ON CONFLICT (key) DO NOTHING;
