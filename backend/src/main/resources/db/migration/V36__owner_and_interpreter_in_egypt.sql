-- V36: the owner's account (Mohamed Ramadan) and every interpreter account belong to Egypt (owner, 2026-10-14), so they
-- see EGP prices and pay by InstaPay. ADMIN source: set on purpose, never overwritten by sign-in detection.
UPDATE users
SET country_code = 'EG', country_source = 'ADMIN'
WHERE role = 'INTERPRETER'
   OR lower(email) LIKE '%@saadatu-aldarein.com'
   OR lower(email) = 'm.ramadansaad@gmail.com'
   OR lower(name) LIKE 'mohamed ramadan%'
   OR name LIKE 'محمد رمضان%';
