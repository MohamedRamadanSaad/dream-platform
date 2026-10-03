#!/usr/bin/env bash
# Go-live reset of the production database (owner's request, 2026-10-03).
#
#   bash go-live-reset.sh              → DRY RUN: takes a backup and prints how many rows each table has. Changes nothing.
#   CONFIRM=RESET-PRODUCTION bash go-live-reset.sh
#                                      → takes a backup (the save point), then runs go-live-reset.sql in one transaction
#                                        and prints the counts after.
#
# Restore the save point (puts the database back exactly as it was at the backup):
#   cd /opt/saadat/deploy && docker compose --env-file .env stop backend
#   docker compose --env-file .env exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < /opt/saadat/backups/<file>.dump
#   docker compose --env-file .env start backend
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cd /opt/saadat/deploy
dc() { docker compose --env-file .env "$@"; }
psql_q() { dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -X -q "$@"' -- "$@"; }

counts() {
  psql_q -A -F ' | ' -c "select 'users (USER)', count(*) from users where role = 'USER'
    union all select 'users (INTERPRETER)', count(*) from users where role = 'INTERPRETER'
    union all select 'dreams', count(*) from dreams
    union all select 'orders', count(*) from orders
    union all select 'credit_ledger', count(*) from credit_ledger
    union all select 'page_views', count(*) from page_views
    union all select 'notifications', count(*) from notifications
    union all select 'support_tickets', count(*) from support_tickets
    union all select 'email_log', count(*) from email_log
    union all select 'countries (kept)', count(*) from countries
    union all select 'packages (kept)', count(*) from packages
    union all select 'price_rules (kept)', count(*) from price_rules
    union all select 'promotions (kept)', count(*) from promotions
    union all select 'coupons (kept)', count(*) from coupons
    union all select 'app_settings (kept)', count(*) from app_settings"
}

mkdir -p /opt/saadat/backups
backup=/opt/saadat/backups/before-go-live-$(date -u +%Y%m%d-%H%M%S).dump
dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup"
size=$(stat -c %s "$backup")
if [ "$size" -lt 10000 ]; then
  echo "backup looks empty ($size bytes): stopping, nothing changed"; exit 1
fi
echo "save point: $backup ($size bytes)"

echo "--- rows now"
counts

if [ "${CONFIRM:-}" != "RESET-PRODUCTION" ]; then
  echo "--- DRY RUN: nothing changed. Run again with CONFIRM=RESET-PRODUCTION to clear the test data."
  exit 0
fi

dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -X -q' < "$here/go-live-reset.sql"
echo "--- rows after the reset"
counts
echo "done. Visitors and their sessions are gone; interpreter accounts must sign in again."
