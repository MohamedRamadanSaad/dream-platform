# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
# READ-ONLY: accounts with their country, to move the owner's and the interpreter's accounts to Egypt.
cd /opt/saadat/deploy
docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
select id, name, email, role, country_code, country_source, deleted, created_at
from users order by role desc, created_at;
SQL
