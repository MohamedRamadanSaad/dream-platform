#!/usr/bin/env bash
# One-time VPS setup. Run as root on Ubuntu 24.04:
#   (called by install.sh; served at https://saadat-aldarain.vercel.app/ops/bootstrap.sh)
set -euo pipefail

APP_DIR=/opt/saadat
DEPLOY_USER=deploy

echo "== system update"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y && apt-get upgrade -y
apt-get install -y ca-certificates curl gnupg ufw fail2ban unattended-upgrades git

echo "== docker"
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -y && apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
systemctl enable --now docker

echo "== deploy user"
id -u $DEPLOY_USER >/dev/null 2>&1 || useradd -m -s /bin/bash -G docker $DEPLOY_USER
mkdir -p /home/$DEPLOY_USER/.ssh && chmod 700 /home/$DEPLOY_USER/.ssh
[ -f /root/.ssh/authorized_keys ] && cp /root/.ssh/authorized_keys /home/$DEPLOY_USER/.ssh/authorized_keys || true
touch /home/$DEPLOY_USER/.ssh/authorized_keys; chmod 600 /home/$DEPLOY_USER/.ssh/authorized_keys
chown -R $DEPLOY_USER:$DEPLOY_USER /home/$DEPLOY_USER/.ssh

echo "== app dir"
mkdir -p $APP_DIR && chown -R $DEPLOY_USER:$DEPLOY_USER $APP_DIR

echo "== firewall"
ufw default deny incoming; ufw default allow outgoing
ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp; ufw allow 443/udp
ufw --force enable

echo "== ssh hardening"
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
systemctl restart ssh || systemctl restart sshd

echo "== auto security updates"
dpkg-reconfigure -f noninteractive unattended-upgrades

echo
echo "DONE. Next:"
echo "  1) put the GitHub Actions public key in /home/$DEPLOY_USER/.ssh/authorized_keys"
echo "  2) create $APP_DIR/deploy/.env from deploy/.env.example"
echo "  3) push to main — GitHub Actions deploys."
