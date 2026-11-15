#!/usr/bin/env bash
# Deploys one backend image tag on the VM, waits for it to be healthy, rolls back if it isn't.
#
# Called by GitHub Actions over SSH with a restricted key; ~/.ssh/authorized_keys pins that key to
# this script (command="…/deploy.sh",restrict), so SSH_ORIGINAL_COMMAND is the ONLY input, e.g.
#   deploy sha-1a2b3c4d5e6f
# Manual use on the VM:   bash infra/scripts/deploy.sh sha-1a2b3c4d5e6f   (or: latest)
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")/../.."          # -> ~/seatlock
ENV_FILE=infra/.env.prod
REPO=ghcr.io/prajjwalpathak/seatlock-backend
compose() { docker compose --env-file "$ENV_FILE" -f infra/docker-compose.prod.yml "$@"; }

# ---- 1. Validate input: only an image tag, nothing else is ever executed ----
request="${SSH_ORIGINAL_COMMAND:-${1:-}}"
tag="${request#deploy }"
if [[ ! "$tag" =~ ^(sha-[0-9a-f]{12}|latest)$ ]]; then
  echo "refused: expected 'deploy sha-<12 hex>' or 'deploy latest'" >&2
  exit 2
fi

# ---- 2. One deploy at a time ----
exec 9>/tmp/seatlock-deploy.lock
flock -n 9 || { echo "another deploy is running" >&2; exit 3; }

new="$REPO:$tag"
prev="$(grep -E '^APP_IMAGE=' "$ENV_FILE" | cut -d= -f2- || true)"

set_image() {   # remember the running image in .env.prod, so manual 'docker compose' commands use it too
  if grep -q '^APP_IMAGE=' "$ENV_FILE"; then sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$1|" "$ENV_FILE"
  else echo "APP_IMAGE=$1" >> "$ENV_FILE"; fi
}

wait_healthy() {   # up to ~2.5 min: covers the 60 s healthcheck start period
  local id status
  for _ in $(seq 1 50); do
    id="$(compose ps -q app)"
    status="$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)"
    [[ "$status" == healthy ]] && return 0
    [[ "$status" == unhealthy ]] && return 1
    sleep 3
  done
  return 1
}

# ---- 3. Pull, switch, verify ----
echo "pulling $new"
docker pull -q "$new"
set_image "$new"
compose up -d --no-deps app            # only the app is replaced; Nginx re-resolves it via Docker DNS

if wait_healthy; then
  echo "deployed $new (previous: ${prev:-none})"
  docker image prune -f >/dev/null     # drop dangling layers; tagged images stay for rollback
  exit 0
fi

# ---- 4. Automatic rollback ----
echo "NEW IMAGE NOT HEALTHY: $new" >&2
compose logs --tail 40 app >&2 || true
if [[ -n "$prev" && "$prev" != "$new" ]]; then
  echo "rolling back to $prev" >&2
  set_image "$prev"
  compose up -d --no-deps app
  if wait_healthy; then echo "rolled back to $prev" >&2; else echo "ROLLBACK ALSO UNHEALTHY" >&2; fi
fi
exit 1
