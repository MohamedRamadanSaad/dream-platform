# Deploying to the Hostinger VPS

One-time (you):
1. On the VPS (as root): `curl -fsSL https://raw.githubusercontent.com/MohamedRamadanSaad/dream-platform/main/deploy/bootstrap.sh | bash`
   (private repo: paste the script over SSH instead).
2. Generate a deploy key on your PC: `ssh-keygen -t ed25519 -C gh-actions -f gh-actions` (no passphrase).
   - Public key → append to `/home/deploy/.ssh/authorized_keys` on the VPS.
   - Private key → GitHub repo → Settings → Secrets and variables → Actions → **Secrets**: `VPS_SSH_KEY`.
   - Also secrets `VPS_HOST` = server IP, `VPS_USER` = `deploy`.
   - **Variables**: `VPS_ENABLED` = `true` (the workflow is skipped until this exists).
3. On the VPS: `mkdir -p /opt/saadat/deploy && nano /opt/saadat/deploy/.env` — copy `deploy/.env.example`, set `DOMAIN`, a strong `POSTGRES_PASSWORD`.
4. Cloudflare DNS: `A` record for `DOMAIN` → VPS IP, proxy ON (orange). SSL/TLS mode: **Full (strict)**.

Every push to `main` then: syncs sources → builds images on the VPS → restarts → smoke-tests https://DOMAIN.

Backend: uncomment the `backend` service in `docker-compose.yml` and the `reverse_proxy backend:8080` line in `Caddyfile` once `backend/` lands; set `VITE_USE_MOCKS=false` in `.env`.

Ops:
- logs: `docker compose -f /opt/saadat/deploy/docker-compose.yml logs -f --tail=200`
- DB backup: `docker compose exec postgres pg_dump -U dreams dreams | gzip > /opt/saadat/backup-$(date +%F).sql.gz`
