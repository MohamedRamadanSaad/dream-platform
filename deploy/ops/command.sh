# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# READ-ONLY: inventory of everything created since the pre-launch reset (2026-10-14 16:24 UTC), to tell real
# customers from test data before anything is removed. Nothing is changed.
set -u
cd /opt/saadat/deploy
docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -P pager=off' <<'SQL'
\echo '== users'
select u.email, u.name, u.role, u.country_code, u.created_at::timestamp(0) created, (u.deleted_at is not null) deleted,
       (select count(*) from orders o where o.user_id = u.id) orders,
       (select count(*) from dreams d where d.user_id = u.id and not d.deleted) dreams,
       (select coalesce(sum(c.delta), 0) from credit_ledger c where c.user_id = u.id) balance
from users u order by u.created_at;
\echo '== orders'
select o.order_number, u.email, o.status, o.failure_reason, o.provider, o.amount, o.currency, o.credits,
       o.transfer_reference, o.transfer_sender_name, o.created_at::timestamp(0) created, o.paid_at::timestamp(0) paid,
       o.approval_revoked_at is not null revoked, o.leftover_withdrawn_credits
from orders o join users u on u.id = o.user_id order by o.created_at;
\echo '== dreams'
select left(d.id::text, 8) id, u.email, d.status, d.created_at::timestamp(0) created, d.submitted_at::timestamp(0) submitted,
       d.interpreted_at::timestamp(0) interpreted, d.deleted, left(regexp_replace(d.text, '\s+', ' ', 'g'), 60) text
from dreams d join users u on u.id = d.user_id order by d.created_at;
\echo '== credit ledger'
select u.email, c.reason, c.kind, c.delta, c.created_at::timestamp(0) created
from credit_ledger c join users u on u.id = c.user_id order by c.created_at;
\echo '== other tables'
select 'notifications' t, count(*) from notifications union all
select 'testimonials', count(*) from testimonials union all
select 'dream_messages', count(*) from dream_messages union all
select 'support_tickets', count(*) from support_tickets union all
select 'coupon_redemptions', count(*) from coupon_redemptions union all
select 'push_subscriptions', count(*) from push_subscriptions where not deleted union all
select 'audit_log since reset', count(*) from audit_log where created_at > '2026-10-14 16:24+00';
SQL
