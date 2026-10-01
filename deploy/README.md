# Deploying to the Hostinger VPS

One-time (you):
1. On the VPS (as root, once):
   `curl -fsSL https://saadat-aldarain.vercel.app/ops/install.sh | bash -s -- saadatu-aldarein.com m.ramadansaad@gmail.com`
   It hardens the server, installs Docker, creates the `deploy` user, writes `/opt/saadat/deploy/.env` and `backend.env`,
   generates the GitHub Actions key and prints the values for step 2.
2. GitHub repo → Settings → Secrets and variables → Actions: **Secrets** `VPS_HOST`, `VPS_USER` (= `deploy`), `VPS_SSH_KEY`;
   **Variable** `VPS_ENABLED` = `true` (the workflow is skipped until this exists).
3. Cloudflare DNS: `A` records `@` and `www` → VPS IP, proxy ON (orange). SSL/TLS mode: **Full (strict)**.
4. Fill the empty values in `/opt/saadat/deploy/backend.env` (SMTP, Google, VAPID, Paymob) when available — see docs/PROJECT_KNOWLEDGE.md.

Every push to `main` then: syncs sources → builds images on the VPS → restarts → smoke-tests https://DOMAIN.


Ops:
- logs: `docker compose -f /opt/saadat/deploy/docker-compose.yml logs -f --tail=200`
- DB backup: `docker compose exec postgres pg_dump -U dreams dreams | gzip > /opt/saadat/backup-$(date +%F).sql.gz`
