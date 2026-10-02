#!/usr/bin/env bash
# Web-push (VAPID) keys, generated ON THE SERVER the first time. deploy-vps.yml runs it after syncing sources:
#   bash /opt/saadat/deploy/ops/vapid-keys.sh support@saadatu-aldarein.com
# Idempotent: keys that exist are never replaced (browser push subscriptions are bound to the public key).
# When VAPID_PRIVATE_KEY is empty it writes VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY and VAPID_SUBJECT=mailto:<contact>
# into backend.env with setenv.sh and touches .recreate-backend. Format = what nl.martijndwars:web-push (and
# `npx web-push generate-vapid-keys`) use: private key = base64url (no padding) of the 32-byte P-256 scalar,
# public key = base64url (no padding) of the 65-byte uncompressed point (0x04 || X || Y).
# The private key never leaves this machine: it only travels through pipes (never argv) and is never printed.
# Output (one line): "generated <public key>" | "subject-set" | "exists".
set -euo pipefail
contact=${1:?usage: vapid-keys.sh <contact e-mail>}
deploy_dir=${DEPLOY_DIR:-/opt/saadat/deploy}
env_file=$deploy_dir/backend.env

# value of KEY in the env file: first match, surrounding quotes and blanks removed ('' when missing)
value_of() {
  local line
  line=$(grep -m1 "^$1=" "$env_file" 2>/dev/null || true)
  line=${line#*=}
  line=${line//[[:space:]]/}
  line=${line#[\'\"]}
  line=${line%[\'\"]}
  printf '%s' "$line"
}

touch "$env_file"
chmod 600 "$env_file" 2>/dev/null || true

if [ -n "$(value_of VAPID_PRIVATE_KEY)" ]; then
  if [ -z "$(value_of VAPID_SUBJECT)" ]; then
    printf 'VAPID_SUBJECT=mailto:%s\n' "$contact" | bash "$deploy_dir/ops/setenv.sh" "$env_file" >/dev/null
    touch "$deploy_dir/.recreate-backend"
    echo "subject-set"
  else
    echo "exists"
  fi
  exit 0
fi

umask 077
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
openssl ecparam -name prime256v1 -genkey -noout -outform DER -out "$tmp/key.der" 2>/dev/null \
  || { echo "openssl could not generate an EC key" >&2; exit 1; }

hex() { od -An -tx1 -v | tr -d ' \n'; }
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }

# SEC1 ECPrivateKey (RFC 5915) of a P-256 key, 121 bytes:
#   30 77 | 02 01 01 | 04 20 <d: 32 bytes> | a0 0a 06 08 <OID prime256v1> | a1 44 03 42 00 | 04 <X: 32> <Y: 32>
if [ "$(wc -c < "$tmp/key.der")" -ne 121 ] \
  || [ "$(head -c 7 "$tmp/key.der" | hex)" != "30770201010420" ] \
  || [ "$(tail -c +40 "$tmp/key.der" | head -c 18 | hex)" != "a00a06082a8648ce3d030107a14403420004" ]; then
  echo "unexpected EC key layout from openssl" >&2
  exit 1
fi
private_key=$(tail -c +8 "$tmp/key.der" | head -c 32 | b64url)
public_key=$(tail -c 65 "$tmp/key.der" | b64url)
# the public key openssl derives from the private key must be the one stored next to it
derived=$(openssl ec -inform DER -in "$tmp/key.der" -pubout -outform DER 2>/dev/null | tail -c 65 | b64url)
if [ "$public_key" != "$derived" ] || [ ${#private_key} -ne 43 ] || [ ${#public_key} -ne 87 ]; then
  echo "generated VAPID key failed its checks" >&2
  exit 1
fi

printf 'VAPID_PUBLIC_KEY=%s\nVAPID_PRIVATE_KEY=%s\nVAPID_SUBJECT=mailto:%s\n' "$public_key" "$private_key" "$contact" \
  | bash "$deploy_dir/ops/setenv.sh" "$env_file" >/dev/null
touch "$deploy_dir/.recreate-backend"
echo "generated $public_key"
