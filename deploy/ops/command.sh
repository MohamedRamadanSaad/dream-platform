# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# ONE-OFF, owner's request 2026-10-17 16:40 Riyadh: remove the owner's own test data (account m.ramadansaad@gmail.com).
# Kept: the account itself, order RN-15004 (a real 100 EGP InstaPay transfer) and its PURCHASE credit, so his balance
# ends at 1 dream. Removed: his 4 test dreams, order RN-15001, every other credit row of his, his notifications.
# (Run 1 at 13:41 UTC only took a save point: pg_dump swallowed the script from stdin, nothing was changed.)
# Full pg_dump save point first; one transaction; aborts (nothing changed) if the data is not exactly as inspected.
set -euo pipefail
cd /opt/saadat/deploy
dc() { docker compose --env-file .env "$@"; }
mkdir -p /opt/saadat/backups
backup=/opt/saadat/backups/before-owner-test-cleanup-$(date -u +%Y%m%d-%H%M%S).dump
dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup" < /dev/null   # the script itself arrives on stdin: never let pg_dump read it
test -s "$backup"
echo "save point: $backup ($(du -h "$backup" | cut -f1))"

dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -P pager=off -v ON_ERROR_STOP=1' <<'SQL'
BEGIN;
CREATE TEMP TABLE t_user ON COMMIT DROP AS SELECT id FROM users WHERE email = 'm.ramadansaad@gmail.com';
CREATE TEMP TABLE t_keep_order ON COMMIT DROP AS
  SELECT id FROM orders WHERE order_number = 'RN-15004' AND user_id IN (SELECT id FROM t_user) AND status = 'SUCCESS';
CREATE TEMP TABLE t_keep_ledger ON COMMIT DROP AS
  SELECT id FROM credit_ledger WHERE reason = 'PURCHASE' AND order_id IN (SELECT id FROM t_keep_order);
CREATE TEMP TABLE t_dreams ON COMMIT DROP AS SELECT id FROM dreams WHERE user_id IN (SELECT id FROM t_user);
CREATE TEMP TABLE t_orders ON COMMIT DROP AS
  SELECT id FROM orders WHERE user_id IN (SELECT id FROM t_user) AND id NOT IN (SELECT id FROM t_keep_order);

-- safety: stop unless the data is exactly what was inspected (1 user, keep 1 order + 1 purchase row, 4 dreams, 1 order to drop)
DO $$
BEGIN
  IF (SELECT count(*) FROM t_user) <> 1 THEN RAISE EXCEPTION 'expected 1 user'; END IF;
  IF (SELECT count(*) FROM t_keep_order) <> 1 THEN RAISE EXCEPTION 'expected RN-15004 SUCCESS'; END IF;
  IF (SELECT count(*) FROM t_keep_ledger) <> 1 THEN RAISE EXCEPTION 'expected 1 PURCHASE row for RN-15004'; END IF;
  IF (SELECT count(*) FROM t_dreams) <> 4 THEN RAISE EXCEPTION 'expected 4 dreams'; END IF;
  IF (SELECT count(*) FROM t_orders) <> 1 THEN RAISE EXCEPTION 'expected 1 order to remove (RN-15001)'; END IF;
  IF EXISTS (SELECT 1 FROM credit_ledger WHERE source_id IN (SELECT id FROM credit_ledger WHERE user_id IN (SELECT id FROM t_user) AND id NOT IN (SELECT id FROM t_keep_ledger))
             AND user_id NOT IN (SELECT id FROM t_user)) THEN RAISE EXCEPTION 'another user references these ledger rows'; END IF;
END $$;

UPDATE dreams SET ledger_entry_id = NULL, held_order_id = NULL WHERE id IN (SELECT id FROM t_dreams);
UPDATE credit_ledger SET source_id = NULL WHERE id IN (SELECT id FROM t_keep_ledger) AND source_id IS NOT NULL;
DELETE FROM credit_ledger WHERE user_id IN (SELECT id FROM t_user) AND id NOT IN (SELECT id FROM t_keep_ledger);
DELETE FROM dreams WHERE id IN (SELECT id FROM t_dreams);          -- messages, interpretations, testimonials cascade
DELETE FROM coupon_redemptions WHERE order_id IN (SELECT id FROM t_orders);
DELETE FROM orders WHERE id IN (SELECT id FROM t_orders);
DELETE FROM notifications WHERE user_id IN (SELECT id FROM t_user);

-- result must be: balance 1, 1 order (RN-15004), 0 dreams
DO $$
DECLARE b int; o int; d int;
BEGIN
  SELECT coalesce(sum(delta), 0) INTO b FROM credit_ledger WHERE user_id IN (SELECT id FROM t_user);
  SELECT count(*) INTO o FROM orders WHERE user_id IN (SELECT id FROM t_user);
  SELECT count(*) INTO d FROM dreams WHERE user_id IN (SELECT id FROM t_user);
  IF b <> 1 OR o <> 1 OR d <> 0 THEN RAISE EXCEPTION 'unexpected result: balance %, orders %, dreams %', b, o, d; END IF;
END $$;
COMMIT;

\echo '== owner account after cleanup'
SELECT u.email,
       (SELECT coalesce(sum(delta), 0) FROM credit_ledger c WHERE c.user_id = u.id) balance,
       (SELECT string_agg(order_number || ' ' || status, ', ') FROM orders o WHERE o.user_id = u.id) orders,
       (SELECT count(*) FROM dreams d WHERE d.user_id = u.id) dreams,
       (SELECT count(*) FROM notifications n WHERE n.user_id = u.id) notifications
FROM users u WHERE u.email = 'm.ramadansaad@gmail.com';
\echo '== other customers untouched'
SELECT u.email, (SELECT count(*) FROM orders o WHERE o.user_id = u.id) orders, (SELECT count(*) FROM dreams d WHERE d.user_id = u.id) dreams
FROM users u WHERE u.email <> 'm.ramadansaad@gmail.com' ORDER BY u.created_at;
SQL
