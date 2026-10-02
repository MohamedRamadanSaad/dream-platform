# Deploying to the Hostinger VPS

One-time (you):
1. On the VPS (as root, once):
   `curl -fsSL https://saadat-aldarain.vercel.app/ops/install.sh | bash -s -- saadatu-aldarein.com m.ramadansaad@gmail.com`
   It hardens the server, installs Docker, creates the `deploy` user, writes `/opt/saadat/deploy/.env` and `backend.env`,
   generates the GitHub Actions key and prints the values for step 2.
2. GitHub repo → Settings → Secrets and variables → Actions: **Secrets** `VPS_HOST`, `VPS_USER` (= `deploy`), `VPS_SSH_KEY`;
   **Variable** `VPS_ENABLED` = `true` (the workflow is skipped until this exists).
3. Cloudflare DNS: `A` records `@` and `www` → VPS IP, proxy ON (orange). SSL/TLS mode: **Full (strict)**.
4. Nothing to fill by hand in `/opt/saadat/deploy/backend.env`: the deploy writes SMTP and Google settings, generates
   the web-push (VAPID) keys on the server, and writes Paymob from GitHub secrets — see docs/PROJECT_KNOWLEDGE.md.

Every push to `main` (and a monthly run on the 3rd) then: syncs sources → settings into the env files → GeoIP database
(`/opt/saadat/geoip`) → push keys → Paymob → builds images on the VPS → restarts → smoke-tests https://DOMAIN.


Ops:
- logs: `docker compose -f /opt/saadat/deploy/docker-compose.yml logs -f --tail=200`
- DB backup: `docker compose exec postgres pg_dump -U dreams dreams | gzip > /opt/saadat/backup-$(date +%F).sql.gz`
