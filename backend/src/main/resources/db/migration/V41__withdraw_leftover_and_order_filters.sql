-- V41 (docs/PAYMENTS_FILTERS_CONTRACT.md): the interpreter withdraws the unused credits of a paid order (ledger REVOKE
-- rows, the order stays SUCCESS and its dreams are untouched), the buyer is told (CREDITS_WITHDRAWN + credits-withdrawn
-- e-mail); indexes for the filters of the payments page.

-- ------------------------------------------------------------------ orders: leftover withdrawal
ALTER TABLE orders ADD COLUMN leftover_withdrawn_at      timestamptz;
ALTER TABLE orders ADD COLUMN leftover_withdrawn_credits integer CONSTRAINT orders_leftover_withdrawn_credits_check
    CHECK (leftover_withdrawn_credits IS NULL OR leftover_withdrawn_credits > 0);
ALTER TABLE orders ADD COLUMN leftover_note              text;

-- ------------------------------------------------------------------ filters of /admin/orders
CREATE INDEX IF NOT EXISTS ix_orders_status_created ON orders (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_orders_created ON orders (created_at DESC);

-- ------------------------------------------------------------------ notification type CREDITS_WITHDRAWN
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
                    'DREAM_STARTED', 'DREAM_EDITED', 'PAYMENT_REVOKED', 'CREDITS_WITHDRAWN'));

-- ------------------------------------------------------------------ e-mail credits-withdrawn (switch + theme)
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.event.credits-withdrawn', 'true', 'BOOL',
     'E-mail the buyer when the interpreter withdraws the unused credits of a payment'),
    ('mail.theme.credits-withdrawn', '', 'STRING', 'E-mail theme of credits-withdrawn; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;
