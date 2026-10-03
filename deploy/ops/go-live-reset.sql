-- Go-live reset: removes all test activity and keeps the fixed data the site runs on.
-- Run ONLY through deploy/ops/go-live-reset.sh (it takes a full backup first). One transaction: any error = nothing
-- changes (TRUNCATE also fails on purpose if a table that references these is missing from the list).
--
-- KEPT (fixed data): app_settings, countries, country_groups, packages, price_rules, promotions, coupons,
--                    youtube_videos, flyway_schema_history, interpreter accounts (users with role INTERPRETER)
--                    with their sign-in methods (auth_identities, passkeys).
-- CLEARED: everything users did while testing (see the TRUNCATE list and the DELETEs below).

BEGIN;

-- 1. activity, money and messages: emptied completely
TRUNCATE TABLE
    page_views,
    email_log,
    audit_log,
    notifications,
    push_subscriptions,
    magic_links,
    refresh_tokens,
    user_sessions,
    passkey_challenges,
    support_ticket_events,
    support_tickets,
    coupon_redemptions,
    testimonials,
    interpretations,
    dream_messages,
    credit_ledger,
    dreams,
    orders,
    user_notes,
    youtube_seen;

-- 2. visitor accounts (role USER) and their sign-in methods; interpreter accounts stay
DELETE FROM passkeys        WHERE user_id IN (SELECT id FROM users WHERE role <> 'INTERPRETER');
DELETE FROM auth_identities WHERE user_id IN (SELECT id FROM users WHERE role <> 'INTERPRETER');
DELETE FROM users           WHERE role <> 'INTERPRETER';

-- 3. offers start again from zero uses
UPDATE promotions SET used_count = 0;
UPDATE coupons    SET used_count = 0;

-- 4. the site mailbox account belongs to Egypt (same as V32)
UPDATE users SET country_code = 'EG', country_source = 'ADMIN'
WHERE lower(email) = 'support@saadatu-aldarein.com';

COMMIT;
