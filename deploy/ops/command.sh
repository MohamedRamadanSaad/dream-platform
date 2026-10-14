# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# READ-ONLY checks after the 2026-10-14 pre-launch reset (run by commit 5d03e1d): the save point it made, the profile
# in use, accounts' countries, what is left in payments and dreams, visitors vs visits.
set -u
cd /opt/saadat/deploy
echo "=== backend mode (non-secret keys only)"
grep -E '^(SPRING_PROFILES_ACTIVE|PAYMENTS_MOCK)=' backend.env || true
docker compose --env-file .env logs backend 2>&1 | grep -iE 'profile(s)? (is )?active|following .* profile' | tail -2 || true


echo "=== checks after the reset"
docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X' <<'SQL'
select name, email, role, country_code from users where deleted_at is null order by role desc, created_at;
select (select count(*) from orders) orders, (select count(*) from credit_ledger) ledger, (select count(*) from dreams) dreams,
       (select count(*) from testimonials) testimonials, (select count(*) from notifications) notifications;
select count(*) as page_views, count(distinct visitor_id) as visitors, count(distinct session_id) as visits,
       count(*) filter (where visitor_id is null) as views_without_visitor from page_views;
-- the dashboard's formula: one visitor = coalesce(visitor_id, session_id); one visit = one session_id
select count(*) views, count(distinct coalesce(visitor_id, session_id)) dashboard_visitors, count(distinct session_id) visits
from page_views;
-- a visit (session) must never belong to two visitors; rows without a visitor id must all be older than the cookie
select count(*) as sessions_with_two_visitors from (select session_id from page_views where visitor_id is not null
  group by session_id having count(distinct visitor_id) > 1) x;
select max(created_at) filter (where visitor_id is null) last_row_without_visitor,
       min(created_at) filter (where visitor_id is not null) first_row_with_visitor, max(created_at) last_view from page_views;
-- visitors who came back: how many visits each (top 5)
select left(visitor_id, 8) visitor, count(distinct session_id) visits, count(*) views from page_views
where visitor_id is not null group by visitor_id order by visits desc limit 5;
SQL
