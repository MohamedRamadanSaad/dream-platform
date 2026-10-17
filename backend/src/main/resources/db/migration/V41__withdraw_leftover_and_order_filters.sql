-- V41 (docs/PAYMENTS_FILTERS_CONTRACT.md): the interpreter withdraws the unused credits of a paid order (ledger REVOKE
-- rows, the order stays SUCCESS and its dreams are untouched), the buyer is told (CREDITS_WITHDRAWN + credits-withdrawn
-- e-mail); indexes for the filters of the payments page.

-- ------------------------------------------------------------------ orders: leftover withdrawal
ALTER TABLE orders ADD COLUMN leftover_withdrawn_at      timestamptz;
ALTER TABLE orders ADD COLUMN leftover_withdrawn_credits integer CONSTRAINT orders_leftover_withdrawn_credits_check
    CHECK (leftover_withdrawn_credits IS NULL OR leftover_withdrawn_credits > 0);
ALTER TABLE orders ADD COLUMN leftover_note              text;
-- why: UNCONFIRMED (the payment could not be confirmed), AMOUNT_SHORT (less money arrived), OTHER (see the note)
ALTER TABLE orders ADD COLUMN leftover_reason            varchar(16) CONSTRAINT orders_leftover_reason_check
    CHECK (leftover_reason IS NULL OR leftover_reason IN ('UNCONFIRMED', 'AMOUNT_SHORT', 'OTHER'));

-- ------------------------------------------------------------------ credit_ledger: what a row was for
-- the credit history tells an undone approval from a withdrawn or restored leftover (REVOKE / REFUND rows)
ALTER TABLE credit_ledger ADD COLUMN kind varchar(24) CONSTRAINT credit_ledger_kind_check
    CHECK (kind IS NULL OR kind IN ('APPROVAL_REVOKED', 'LEFTOVER_WITHDRAWN', 'LEFTOVER_RESTORED'));
-- before V41 every REVOKE row (and the REFUND of a dream held by an undo, written with note APPROVAL_REVOKED)
-- came from an undone approval: production may already hold such rows from V40
UPDATE credit_ledger SET kind = 'APPROVAL_REVOKED' WHERE reason = 'REVOKE' AND kind IS NULL;
UPDATE credit_ledger SET kind = 'APPROVAL_REVOKED'
WHERE reason = 'REFUND' AND kind IS NULL AND note = 'APPROVAL_REVOKED' AND dream_id IS NOT NULL;
-- from now on a REVOKE row always says what it was for
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_revoke_kind_check CHECK (reason <> 'REVOKE' OR kind IS NOT NULL);

-- ------------------------------------------------------------------ filters of /admin/orders
CREATE INDEX IF NOT EXISTS ix_orders_status_created ON orders (status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_orders_created ON orders (created_at DESC);

-- ------------------------------------------------------------------ notification types CREDITS_WITHDRAWN, CREDITS_RESTORED
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
                    'DREAM_STARTED', 'DREAM_EDITED', 'PAYMENT_REVOKED', 'CREDITS_WITHDRAWN', 'CREDITS_RESTORED'));

-- ------------------------------------------------------------------ e-mail credits-withdrawn (switch + theme)
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.event.credits-withdrawn', 'true', 'BOOL',
     'E-mail the buyer when the interpreter withdraws the unused credits of a payment'),
    ('mail.theme.credits-withdrawn', '', 'STRING', 'E-mail theme of credits-withdrawn; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;
