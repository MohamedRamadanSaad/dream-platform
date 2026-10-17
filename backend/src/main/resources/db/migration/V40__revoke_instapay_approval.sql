-- V40 (docs/REVOKE_APPROVAL_CONTRACT.md): the interpreter takes back a mistaken InstaPay approval. The order becomes
-- FAILED / APPROVAL_REVOKED, its unused credits are withdrawn (ledger REVOKE), and the dreams it paid for wait as
-- AWAITING_PAYMENT (dreams.held_order_id) until the buyer resends the transfer and it is approved again.

-- ------------------------------------------------------------------ dreams: status AWAITING_PAYMENT + held order
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
    CHECK (status IN ('DRAFT', 'IN_REVIEW', 'IN_PROGRESS', 'AWAITING_USER_REPLY', 'AWAITING_PAYMENT', 'INTERPRETED',
                      'CANCELLED'));

-- the order whose approval was revoked; set only while the dream is AWAITING_PAYMENT
ALTER TABLE dreams ADD COLUMN held_order_id uuid REFERENCES orders (id);
CREATE INDEX ix_dreams_held_order ON dreams (held_order_id) WHERE held_order_id IS NOT NULL;

-- ------------------------------------------------------------------ credit_ledger: reason REVOKE
DO $$
DECLARE c text;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'credit_ledger'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%reason%'
    LOOP
        EXECUTE format('ALTER TABLE credit_ledger DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_check
    CHECK (reason IN ('PURCHASE', 'SUBMIT', 'REFUND', 'MANUAL', 'BONUS', 'EXPIRE', 'REVOKE'));

-- Idempotency guards, now per "cycle" (source_id), because a revoked order can be approved again and its dreams
-- paid again:
--   PURCHASE: one per order and source (source_id = the earlier, revoked PURCHASE row of the same order; NULL the
--             first time), so two concurrent confirmations of the same order still collide;
--   SUBMIT:   one per dream and lot (source_id = the lot the credit came from);
--   REFUND:   one per dream and debit (source_id = the SUBMIT row it gives back; NULL for rows before V40).
DROP INDEX IF EXISTS ux_credit_ledger_purchase_order;
CREATE UNIQUE INDEX ux_credit_ledger_purchase_order ON credit_ledger (order_id, source_id) NULLS NOT DISTINCT
    WHERE reason = 'PURCHASE' AND order_id IS NOT NULL;
DROP INDEX IF EXISTS ux_credit_ledger_submit_dream;
CREATE UNIQUE INDEX ux_credit_ledger_submit_dream ON credit_ledger (dream_id, source_id) NULLS NOT DISTINCT
    WHERE reason = 'SUBMIT' AND dream_id IS NOT NULL;
DROP INDEX IF EXISTS ux_credit_ledger_refund_dream;
CREATE UNIQUE INDEX ux_credit_ledger_refund_dream ON credit_ledger (dream_id, source_id) NULLS NOT DISTINCT
    WHERE reason = 'REFUND' AND dream_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_credit_ledger_order ON credit_ledger (order_id) WHERE order_id IS NOT NULL;

-- ------------------------------------------------------------------ notification type PAYMENT_REVOKED
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
                    'DREAM_STARTED', 'DREAM_EDITED', 'PAYMENT_REVOKED'));

-- ------------------------------------------------------------------ e-mail payment-review-needed (switch + theme)
INSERT INTO app_settings (key, value, type, description) VALUES
    ('mail.event.payment-review-needed', 'true', 'BOOL',
     'E-mail the buyer when the interpreter undoes an InstaPay approval and asks him to check and resend the transfer'),
    ('mail.theme.payment-review-needed', '', 'STRING', 'E-mail theme of payment-review-needed; empty = mail.theme.default')
ON CONFLICT (key) DO NOTHING;

-- ------------------------------------------------------------------ BA/QA review of the undo (2026-10-17)
-- when the hold started (admin queue "Waiting for payment", oldest hold first)
ALTER TABLE dreams ADD COLUMN held_at timestamptz;

-- the undo history, kept when the buyer resends (review_note / failure_reason are reset by the resend)
ALTER TABLE orders ADD COLUMN approval_revoked_at timestamptz;
ALTER TABLE orders ADD COLUMN revoke_note         text;
-- the confirmation of this order counted one use of its promotion (an undo gives back only what was counted)
ALTER TABLE orders ADD COLUMN promotion_counted   boolean NOT NULL DEFAULT false;
UPDATE orders SET promotion_counted = true
WHERE promotion_id IS NOT NULL AND (status IN ('SUCCESS', 'REFUNDED') OR paid_at IS NOT NULL);

-- a redemption counted one use of its coupon (false when max_uses was already reached); an undo keeps the row as
-- revoked (revoked_at), so a single-use coupon cannot be used again meanwhile, and a new approval restores it
ALTER TABLE coupon_redemptions ADD COLUMN counted    boolean NOT NULL DEFAULT true;
ALTER TABLE coupon_redemptions ADD COLUMN revoked_at timestamptz;

-- a revoked order keeps its transfer reference: no other order may take it until the buyer changes it
DROP INDEX IF EXISTS ux_orders_transfer_reference;
CREATE UNIQUE INDEX ux_orders_transfer_reference ON orders (transfer_reference)
    WHERE transfer_reference IS NOT NULL
      AND (status IN ('PENDING_REVIEW', 'SUCCESS', 'SUSPICIOUS', 'REFUNDED')
           OR (status = 'FAILED' AND failure_reason = 'APPROVAL_REVOKED'));
