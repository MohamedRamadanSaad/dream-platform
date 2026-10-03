# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
C="docker compose -f /opt/saadat/deploy/docker-compose.yml --env-file /opt/saadat/deploy/.env"
echo "== payment mode in backend.env"
grep -E '^(PAYMENTS_MOCK|KASHIER_MODE|SPRING_PROFILES_ACTIVE)=' /opt/saadat/deploy/backend.env || true
for k in KASHIER_MERCHANT_ID KASHIER_API_KEY KASHIER_SECRET_KEY; do
  if grep -qE "^$k=.+" /opt/saadat/deploy/backend.env; then echo "$k: set"; else echo "$k: EMPTY or missing"; fi
done
echo "== containers"
$C ps || true
echo "== backend log: payments"
$C logs --tail=3000 backend 2>&1 | grep -i -E "kashier|payment|checkout|Started|ERROR" | tail -60 || true
