-- V38: readable order numbers (RN-15001, RN-15002 ...) shown everywhere instead of the UUID. The full string is stored,
-- so a later change of the prefix (setting orders.number_prefix) never changes old numbers. The application sets the
-- number on insert (prefix from the setting + nextval); the column DEFAULT covers rows inserted by plain SQL.

CREATE SEQUENCE order_number_seq START WITH 15001 INCREMENT BY 1;

ALTER TABLE orders ADD COLUMN order_number varchar(32);

-- existing orders: numbered in the order they were created
UPDATE orders o
SET order_number = 'RN-' || (15000 + r.rn)
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM orders) r
WHERE o.id = r.id;

-- the next number follows the backfilled ones (15001 when there were none)
SELECT setval('order_number_seq', 15000 + (SELECT count(*) FROM orders), true);

ALTER TABLE orders ALTER COLUMN order_number SET DEFAULT ('RN-' || nextval('order_number_seq'));
ALTER TABLE orders ALTER COLUMN order_number SET NOT NULL;
CREATE UNIQUE INDEX ux_orders_order_number ON orders (order_number);
ALTER SEQUENCE order_number_seq OWNED BY orders.order_number;

INSERT INTO app_settings (key, value, type, description) VALUES
    ('orders.number_prefix', 'RN', 'STRING', 'Prefix of new order numbers (RN-15001); old numbers never change')
ON CONFLICT (key) DO NOTHING;
