# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# 2026-10-14 pre-launch (owner confirmed): backup, then clear every test payment and test dream; keep accounts and visits.
# Then print the checks: profile in use, accounts' countries, visitors vs visits.
set -u
cd /opt/saadat/deploy
echo "=== backend mode (non-secret keys only)"
grep -E '^(SPRING_PROFILES_ACTIVE|PAYMENTS_MOCK)=' backend.env || true
docker compose --env-file .env logs backend 2>&1 | grep -iE 'profile(s)? (is )?active|following .* profile' | tail -2 || true

echo "=== pre-launch reset"
CONFIRM=RESET-PRODUCTION RESET_SQL=prelaunch-reset.sql bash /opt/saadat/deploy/ops/go-live-reset.sh

echo "=== checks after the reset"
docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X' <<'SQL'
select name, email, role, country_code from users where deleted = false order by role desc, created_at;
select (select count(*) from orders) orders, (select count(*) from credit_ledger) ledger, (select count(*) from dreams) dreams,
       (select count(*) from testimonials) testimonials, (select count(*) from notifications) notifications;
select count(*) as page_views, count(distinct visitor_id) as visitors, count(distinct session_id) as visits,
       count(*) filter (where visitor_id is null) as views_without_visitor from page_views;
select key, value from app_settings where key like 'payments.instapay.%' order by key;
SQL
