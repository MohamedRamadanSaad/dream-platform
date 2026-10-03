-- V23: purchased credits expire. Each PURCHASE row gets expires_at = order paid_at + the package's validity_months
-- (NULL = never). An hourly job (CreditExpiryJob) writes one EXPIRE row per expired purchase for the part not used
-- (source_id = that purchase row) and marks the purchase settled. Spending uses the soonest-expiring credits first
-- (CreditLots). Manual credits and refunds never expire. Balance stays SUM(delta).
-- drop the reason CHECK whatever its generated name is
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
    CHECK (reason IN ('PURCHASE', 'SUBMIT', 'REFUND', 'MANUAL', 'BONUS', 'EXPIRE'));

ALTER TABLE credit_ledger
    ADD COLUMN expires_at        timestamptz,
    ADD COLUMN source_id         uuid REFERENCES credit_ledger (id),
    ADD COLUMN expiry_settled_at timestamptz;

-- one EXPIRE row per purchase at most
CREATE UNIQUE INDEX ux_credit_ledger_expire_source ON credit_ledger (source_id) WHERE reason = 'EXPIRE';
-- the job's lookup: purchases past expiry and not settled yet
CREATE INDEX ix_credit_ledger_expiry_due ON credit_ledger (expires_at)
    WHERE expires_at IS NOT NULL AND expiry_settled_at IS NULL;

-- existing purchases: expiry from their order's payment date and the package's current validity
UPDATE credit_ledger l
SET expires_at = o.paid_at + make_interval(months => p.validity_months)
FROM orders o
JOIN packages p ON p.id = o.package_id
WHERE l.reason = 'PURCHASE' AND l.order_id = o.id
  AND o.paid_at IS NOT NULL AND p.validity_months IS NOT NULL;

INSERT INTO app_settings (key, value, type, description) VALUES
    ('credits.expiry_notice_days', '60', 'INT', 'Show the user their next credit expiry when it is within N days')
ON CONFLICT (key) DO NOTHING;
