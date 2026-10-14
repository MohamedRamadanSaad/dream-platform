-- V34: manual InstaPay transfers inside Egypt (EGP). Kashier refused the activity, so an Egyptian buyer transfers
-- the exact amount with the InstaPay app, then types the transfer reference number; the interpreter confirms the
-- money arrived and only then are the credits added (PaymentService.confirm, same path as a gateway webhook).

-- provider INSTAPAY
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_provider_check;
ALTER TABLE orders ADD CONSTRAINT orders_provider_check CHECK (provider IN ('KASHIER', 'MOR', 'MOCK', 'INSTAPAY'));

-- status PENDING_REVIEW (the V4 status CHECK has a name chosen by PostgreSQL: drop it by its definition)
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'orders' AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%SUSPICIOUS%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('INITIATED', 'PENDING_REVIEW', 'SUCCESS', 'FAILED', 'EXPIRED', 'REFUNDED', 'SUSPICIOUS'));

-- what the buyer typed after the transfer, and the interpreter's decision
ALTER TABLE orders ADD COLUMN transfer_reference    varchar(32);
ALTER TABLE orders ADD COLUMN transfer_sender_name  varchar(120);
ALTER TABLE orders ADD COLUMN transfer_submitted_at timestamptz;
ALTER TABLE orders ADD COLUMN reviewed_by           uuid REFERENCES users (id);
ALTER TABLE orders ADD COLUMN reviewed_at           timestamptz;
ALTER TABLE orders ADD COLUMN review_note           text;

-- one InstaPay reference can pay one order only (a rejected order frees it: it may have been a typing mistake)
CREATE UNIQUE INDEX ux_orders_transfer_reference ON orders (transfer_reference)
    WHERE transfer_reference IS NOT NULL AND status IN ('PENDING_REVIEW', 'SUCCESS', 'SUSPICIOUS', 'REFUNDED');
CREATE INDEX ix_orders_pending_review ON orders (transfer_submitted_at) WHERE status = 'PENDING_REVIEW';

-- notification types TRANSFER_SUBMITTED (to interpreters) and PAYMENT_REJECTED (to the payer)
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
                    'PAYMENT_SUCCESS', 'PROMOTION', 'YOUTUBE_VIDEO', 'TRANSFER_SUBMITTED', 'PAYMENT_REJECTED'));

-- the receiving account: editable by the interpreter at any time (Orders page, InstaPay card)
INSERT INTO app_settings (key, value, type, description) VALUES
    ('payments.instapay.enabled',        'true',                                                 'BOOL',
     'Egyptian buyers pay by InstaPay transfer (off: EGP goes to the card gateway)'),
    ('payments.instapay.address',        'mohamedramadansaad@instapay',                          'STRING',
     'InstaPay address (IPA) that receives the transfers'),
    ('payments.instapay.link',           'https://ipn.eg/S/mohamedramadansaad/instapay/2MKvsT',  'STRING',
     'InstaPay payment link of that account (the QR code on the payment page is drawn from it)'),
    ('payments.instapay.recipient_name', '',                                                     'STRING',
     'Name the buyer sees as receiver in InstaPay (empty hides it)'),
    ('payments.instapay.expire_hours',   '24',                                                   'INT',
     'Hours a buyer has to transfer and send the reference before the order expires')
ON CONFLICT (key) DO NOTHING;
