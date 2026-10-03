-- V23: Kashier replaces Paymob as the Egyptian gateway (EGP). Only test orders ever used PAYMOB.
-- Drop whatever CHECK constraint guards orders.provider (named by PostgreSQL in V4), then re-add it.
DO $$
DECLARE c record;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'orders' AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%provider%'
          AND pg_get_constraintdef(con.oid) ILIKE '%PAYMOB%'
    LOOP
        EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

UPDATE orders SET provider = 'KASHIER' WHERE provider = 'PAYMOB';

ALTER TABLE orders ADD CONSTRAINT orders_provider_check CHECK (provider IN ('KASHIER', 'MOR', 'MOCK'));
