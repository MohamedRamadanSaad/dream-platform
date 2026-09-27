#!/usr/bin/env bash
# All-in-one VPS setup. Run ONCE as root on the fresh Ubuntu 24.04 VPS:
#   bash install.sh saadatu-aldarein.com m.ramadansaad@gmail.com
# It: hardens the server, installs Docker, creates the deploy user, generates the GitHub Actions
# SSH key, writes /opt/saadat/deploy/.env, and prints the 3 values to paste into GitHub secrets.
set -euo pipefail
DOMAIN="${1:?usage: install.sh <domain> <acme-email>}"
ACME_EMAIL="${2:?usage: install.sh <domain> <acme-email>}"
APP_DIR=/opt/saadat
DEPLOY_USER=deploy

curl -fsSL -o /tmp/bootstrap.sh https://raw.githubusercontent.com/MohamedRamadanSaad/dream-platform/main/deploy/bootstrap.sh 2>/dev/null || true
if [ -s /tmp/bootstrap.sh ]; then bash /tmp/bootstrap.sh; else
  echo "!! could not fetch bootstrap.sh (private repo). Paste bootstrap.sh manually first."; exit 1; fi

echo "== github actions key"
KEY=/home/$DEPLOY_USER/.ssh/gh-actions
if [ ! -f "$KEY" ]; then
  sudo -u $DEPLOY_USER ssh-keygen -t ed25519 -N "" -C gh-actions -f "$KEY" >/dev/null
  cat "$KEY.pub" >> /home/$DEPLOY_USER/.ssh/authorized_keys
fi

echo "== env file"
mkdir -p $APP_DIR/deploy
if [ ! -f $APP_DIR/deploy/.env ]; then
  cat > $APP_DIR/deploy/.env <<ENV
DOMAIN=$DOMAIN
ACME_EMAIL=$ACME_EMAIL
VITE_API_URL=/api
VITE_USE_MOCKS=true
VITE_GOOGLE_CLIENT_ID=
POSTGRES_DB=dreams
POSTGRES_USER=dreams
POSTGRES_PASSWORD=$(openssl rand -hex 24)
ENV
fi
chown -R $DEPLOY_USER:$DEPLOY_USER $APP_DIR
IP=$(curl -4 -s https://ifconfig.me || hostname -I | awk '{print $1}')

cat <<OUT

==================== PASTE INTO GITHUB ====================
Repo -> Settings -> Secrets and variables -> Actions

[Secrets]
VPS_HOST = $IP
VPS_USER = $DEPLOY_USER
VPS_SSH_KEY = (everything between the lines below, including BEGIN/END)
-----------------------------------------------------------
$(cat "$KEY")
-----------------------------------------------------------

[Variables]
VPS_ENABLED = true
===========================================================
Cloudflare DNS: A  @   -> $IP  (proxied)   |  A  www -> $IP (proxied)  |  SSL: Full (strict)
OUT
