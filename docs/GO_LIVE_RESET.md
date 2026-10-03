# Go-live reset and production database access

## Save point + reset (deploy/ops/go-live-reset.sh)

Run on the server (`ssh root@<VPS_HOST>`):

```
bash /opt/saadat/deploy/ops/go-live-reset.sh                                # dry run: backup + row counts, no change
CONFIRM=RESET-PRODUCTION bash /opt/saadat/deploy/ops/go-live-reset.sh       # backup, then reset
```

Every run first writes a full backup (`pg_dump -Fc`) to `/opt/saadat/backups/before-go-live-<UTC time>.dump` — the
save point. The script stops if the backup is empty. The reset runs in one transaction (all or nothing).
Restore steps are at the top of the script. Tested in CI by `GoLiveResetScriptIntegrationTest` (runs the SQL against
the real schema and rolls back).

### Tables

| Kept (fixed data) | Cleared completely | Cleared partly |
|---|---|---|
| app_settings | page_views | users: only role USER (interpreter accounts stay) |
| countries | email_log | auth_identities: those of removed users |
| country_groups | audit_log | passkeys: those of removed users |
| packages | notifications | promotions: kept, `used_count` back to 0 |
| price_rules | push_subscriptions | coupons: kept, `used_count` back to 0 |
| promotions | magic_links | |
| coupons | refresh_tokens (everyone signs in again) | |
| youtube_videos (channel cache) | user_sessions | |
| flyway_schema_history | passkey_challenges | |
| | support_ticket_events, support_tickets | |
| | coupon_redemptions | |
| | testimonials, interpretations, dream_messages, dreams | |
| | credit_ledger, orders | |
| | user_notes, youtube_seen | |

The account `support@saadatu-aldarein.com` is set to Egypt (also done once by migration V32).

## Connecting to the production database

PostgreSQL 16 runs in Docker on the VPS and listens on the server's loopback only (`127.0.0.1:5432`), never on the
internet. Connect through an SSH tunnel:

```
ssh -N -L 5433:127.0.0.1:5432 root@<VPS_HOST>
```

Then in DBeaver / DataGrip / psql: host `localhost`, port `5433`, database `dreams`, user `dreams`, password = the
`POSTGRES_PASSWORD` line of `/opt/saadat/deploy/.env` on the server:

```
grep ^POSTGRES_PASSWORD= /opt/saadat/deploy/.env
```

JDBC URL: `jdbc:postgresql://localhost:5433/dreams`. Rule: native SQL on soft-deleted tables must filter `not deleted`.
