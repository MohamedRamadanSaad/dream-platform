-- V32: the site mailbox account support@saadatu-aldarein.com belongs to Egypt (owner, 2026-10-03), so it sees EGP
-- prices and counts under Egypt. ADMIN source: set on purpose, never overwritten by sign-in detection.
UPDATE users
SET country_code = 'EG', country_source = 'ADMIN'
WHERE lower(email) = 'support@saadatu-aldarein.com';
