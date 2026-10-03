# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# READ-ONLY: page views of yesterday and today (business time zone), to explain the traffic chart.
cd /opt/saadat/deploy
docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
select value as business_tz, now() as db_now from app_settings where key = 'schedule.time_zone';
select count(*) as all_views, min(created_at) as first_view, max(created_at) as last_view from page_views;
with tz as (select value as z from app_settings where key = 'schedule.time_zone')
select cast(pv.created_at at time zone tz.z as date) as day, coalesce(pv.country_code, '--') as cc, pv.device,
       count(*) as views, count(distinct pv.session_id) as visits,
       count(distinct coalesce(pv.visitor_id, pv.session_id)) as visitors,
       count(pv.visitor_id) as rows_with_visitor_id, count(pv.user_id) as rows_signed_in,
       to_char(min(pv.created_at) at time zone tz.z, 'HH24:MI') as first_at,
       to_char(max(pv.created_at) at time zone tz.z, 'HH24:MI') as last_at
from page_views pv, tz
where pv.created_at >= (date_trunc('day', now() at time zone tz.z) - interval '1 day') at time zone tz.z
group by 1, 2, 3 order by 1, 2, 3;
with tz as (select value as z from app_settings where key = 'schedule.time_zone')
select to_char(pv.created_at at time zone tz.z, 'MM-DD HH24:MI') as at, pv.path, pv.country_code as cc, pv.device,
       left(pv.session_id, 8) as visit, left(coalesce(pv.visitor_id, '-'), 8) as visitor, pv.user_id is not null as signed_in
from page_views pv, tz order by pv.created_at desc limit 25;
SQL
