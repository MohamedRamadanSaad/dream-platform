-- Pre-launch reset (owner's request, 2026-10-14): every payment so far was a test. Clears the money and the test dreams
-- and logs, KEEPS the accounts and the visits. Run ONLY through deploy/ops/go-live-reset.sh with
-- RESET_SQL=prelaunch-reset.sql (it takes a full backup first). One transaction: any error = nothing changes
-- (TRUNCATE also fails on purpose if a table that references these is missing from the list).
--
-- KEPT: users and their sign-in methods (auth_identities, passkeys, refresh_tokens, user_sessions), page_views (visitor
--       and visit statistics), push_subscriptions, support tickets, user_notes, and all fixed data (app_settings,
--       countries, country_groups, packages, price_rules, promotions, coupons, youtube_videos, flyway_schema_history),
--       youtube_seen (which channel videos each user already saw, so the YouTube badge does not light up again).
-- CLEARED: orders, credit_ledger, coupon_redemptions; dreams with their messages, interpretations and testimonials;
--          notifications, email_log, audit_log, magic_links, passkey_challenges. Order numbers restart at 15001.

BEGIN;

TRUNCATE TABLE
    coupon_redemptions,
    credit_ledger,
    orders,
    testimonials,
    interpretations,
    dream_messages,
    dreams,
    notifications,
    email_log,
    audit_log,
    magic_links,
    passkey_challenges;

-- offers start again from zero uses
UPDATE promotions SET used_count = 0;
UPDATE coupons    SET used_count = 0;

-- order numbers start again at RN-15001 (V38)
ALTER SEQUENCE order_number_seq RESTART WITH 15001;

COMMIT;
