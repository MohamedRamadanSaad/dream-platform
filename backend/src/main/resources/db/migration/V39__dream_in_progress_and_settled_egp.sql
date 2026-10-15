-- V39 (docs/UX_PAYMENTS_REVIEW_CONTRACT.md): "Start interpreting" (dream status IN_PROGRESS with the expected number
-- of days), the dreamer's edits before that point, the settled amount of every paid order in EGP (the base currency
-- of the interpreter's revenue ranking), the DREAM_STARTED notification + dream-started e-mail, and the DREAM_EDITED
-- notification to interpreters.

-- ------------------------------------------------------------------ dreams: status IN_PROGRESS
-- the V5 status CHECK has a name chosen by PostgreSQL: drop it by its definition
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'dreams' AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%AWAITING_USER_REPLY%'
    LOOP
        EXECUTE format('ALTER TABLE dreams DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE dreams ADD CONSTRAINT dreams_status_check
    CHECK (status IN ('DRAFT', 'IN_REVIEW', 'IN_PROGRESS', 'AWAITING_USER_REPLY', 'INTERPRETED', 'CANCELLED'));

-- when the interpreter pressed "Start interpreting", the days she expects to need, the dreamer's last edit
ALTER TABLE dreams ADD COLUMN started_at     timestamptz;
ALTER TABLE dreams ADD COLUMN expected_days  integer CONSTRAINT dreams_expected_days_check
    CHECK (expected_days BETWEEN 1 AND 30);
ALTER TABLE dreams ADD COLUMN user_edited_at timestamptz;

-- ------------------------------------------------------------------ orders: settled amount in EGP
ALTER TABLE orders ADD COLUMN settled_amount_egp numeric(12, 2);

-- EGP orders: the amount itself
UPDATE orders
SET settled_amount_egp = amount
WHERE settled_amount_egp IS NULL
  AND currency = 'EGP'
  AND (status IN ('SUCCESS', 'REFUNDED') OR paid_at IS NOT NULL);

-- other currencies: amount × rate(currency) / rate(EGP), rates = value of one unit in USD (setting pricing.fx_to_usd)
WITH fx AS (
    SELECT value::jsonb AS rates
    FROM app_settings
    WHERE key = 'pricing.fx_to_usd' AND value IS NOT NULL AND btrim(value) <> ''
)
UPDATE orders o
SET settled_amount_egp = round(o.amount * (fx.rates ->> btrim(o.currency))::numeric
                               / nullif((fx.rates ->> 'EGP')::numeric, 0), 2)
FROM fx
WHERE o.settled_amount_egp IS NULL
  AND o.currency <> 'EGP'
  AND (o.status IN ('SUCCESS', 'REFUNDED') OR o.paid_at IS NOT NULL)
  AND (fx.rates ->> btrim(o.currency)) IS NOT NULL
  AND (fx.rates ->> 'EGP') IS NOT NULL;

-- the interpreter's order tabs: domestic (Egypt) / international, newest first
CREATE INDEX IF NOT EXISTS ix_orders_country_created ON orders (country_code, created_at DESC);

-- ------------------------------------------------------------------ notification types DREAM_STARTED, DREAM_EDITED
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check;
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'notifications' AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%YOUTUBE_VIDEO%'
    LOOP
        EXECUTE format('ALTER TABLE notifications DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('DREAM_SUBMITTED', 'DREAM_RECEIVED', 'INTERPRETER_QUESTION', 'USER_REPLIED', 'INTERPRETATION_READY',
                    'PAYMENT_SUCCESS', 'PROMOTION', 'YOUTUBE_VIDEO', 'TRANSFER_SUBMITTED', 'PAYMENT_REJECTED',
                    'DREAM_STARTED', 'DREAM_EDITED'));

-- ------------------------------------------------------------------ e-mail dream-started (switch + theme)
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.event.dream-started', 'true', 'BOOL',
     'E-mail the dreamer when the interpreter starts interpreting (with the expected number of days)'),
    ('mail.theme.dream-started', '', 'STRING', 'E-mail theme of dream-started; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;
