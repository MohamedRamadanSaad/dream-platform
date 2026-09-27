# Remote-hands channel: any change to this file runs it on the VPS (as the deploy user, in /opt/saadat)
# and the output is committed back to deploy/ops/last-output.log
echo "hello from $(hostname) at $(date -u)"
docker compose -f /opt/saadat/deploy/docker-compose.yml --env-file /opt/saadat/deploy/.env ps || true
