#!/usr/bin/env bash
# Creates infra/.env.prod ON THE VM with freshly generated secrets, so no secret
# ever travels through chat, Git or your laptop.
#   bash infra/scripts/init-env-prod.sh
# Generates: POSTGRES_PASSWORD, JWT key pair (RS256, PKCS#8 / X.509 DER, base64),
#            SEATLOCK_ADMIN_PASSWORD (printed ONCE - save it in your password manager).
# You still fill in by hand: LETSENCRYPT_EMAIL, SEATLOCK_ADMIN_EMAIL, GOOGLE_*, FRONTEND_BASE_URL, CORS_ALLOWED_ORIGINS.
set -euo pipefail

cd "$(dirname "$0")/.."            # -> infra/
target=.env.prod
if [[ -e "$target" ]]; then
  echo "infra/$target already exists - refusing to overwrite (delete it first if you really mean it)." >&2
  exit 1
fi

umask 077                           # new files readable by this user only
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/jwt.pem" 2>/dev/null
jwt_private=$(openssl pkcs8 -topk8 -nocrypt -in "$tmp/jwt.pem" -outform DER | base64 -w0)
jwt_public=$(openssl pkey -in "$tmp/jwt.pem" -pubout -outform DER | base64 -w0)
pg_password=$(openssl rand -hex 24)
admin_password=$(openssl rand -base64 18 | tr -d '/+=')

sed -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${pg_password}|" \
    -e "s|^JWT_PRIVATE_KEY=.*|JWT_PRIVATE_KEY=${jwt_private}|" \
    -e "s|^JWT_PUBLIC_KEY=.*|JWT_PUBLIC_KEY=${jwt_public}|" \
    -e "s|^SEATLOCK_ADMIN_PASSWORD=.*|SEATLOCK_ADMIN_PASSWORD=${admin_password}|" \
    .env.prod.example > "$target"

chmod 600 "$target"
echo "Created infra/$target (mode 600) with generated DB password and JWT keys."
echo
echo "ADMIN password (shown once, store it now): ${admin_password}"
echo
echo "Next: nano infra/$target  -> fill LETSENCRYPT_EMAIL, SEATLOCK_ADMIN_EMAIL, GOOGLE_*, FRONTEND_BASE_URL, CORS_ALLOWED_ORIGINS"
