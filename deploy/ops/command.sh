# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# READ-ONLY: where the visitor ids "owner-1"/"owner-2" and the one visit with two visitors come from.
set -u
cd /opt/saadat/deploy
docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X' <<'SQL'
select visitor_id, min(created_at) first_seen, max(created_at) last_seen, count(*) views, count(distinct session_id) visits,
       max(device) device, max(referrer_host) ref, string_agg(distinct path, ' ') paths, max(country_code) country, count(distinct user_id) users
from page_views where visitor_id like 'owner-%' or visitor_id !~ '^[0-9a-f-]{20,}$' group by visitor_id order by 2;
select session_id, visitor_id, min(created_at), max(created_at), count(*) from page_views
where session_id in (select session_id from page_views where visitor_id is not null group by session_id having count(distinct visitor_id) > 1)
group by 1, 2;
SQL
