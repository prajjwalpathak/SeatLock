#!/usr/bin/env bash
# Rolling deploy of one backend image tag on the VM: app-1, then app-2 (W5·D5), each must turn healthy
# before the next is touched; any failure rolls both back. Nginx keeps serving from the other instance.
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

APPS=(app-1 app-2)

wait_healthy() {   # $1 = service; up to ~2.5 min: covers the 60 s healthcheck start period
  local id status
  for _ in $(seq 1 50); do
    id="$(compose ps -q "$1")"
    status="$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)"
    [[ "$status" == healthy ]] && return 0
    [[ "$status" == unhealthy ]] && return 1
    sleep 3
  done
  return 1
}

roll() {   # replaces each instance in turn; returns the first one that didn't turn healthy
  local svc
  for svc in "${APPS[@]}"; do
    echo "replacing $svc"
    compose up -d --no-deps "$svc"     # only this instance; the other keeps serving (Nginx skips the one restarting)
    if ! wait_healthy "$svc"; then failed="$svc"; return 1; fi
    echo "$svc healthy"
  done
}

# ---- 3. Pull, switch one instance at a time, verify ----
echo "pulling $new"
docker pull -q "$new"
set_image "$new"
failed=""
if roll; then
  echo "deployed $new to ${APPS[*]} (previous: ${prev:-none})"
  docker image prune -f >/dev/null     # drop dangling layers; tagged images stay for rollback
  exit 0
fi

# ---- 4. Automatic rollback (both instances back to the previous image) ----
echo "NEW IMAGE NOT HEALTHY on $failed: $new" >&2
compose logs --tail 40 "$failed" >&2 || true
if [[ -n "$prev" && "$prev" != "$new" ]]; then
  echo "rolling back to $prev" >&2
  set_image "$prev"
  if roll; then echo "rolled back to $prev" >&2; else echo "ROLLBACK ALSO UNHEALTHY on $failed" >&2; fi
fi
exit 1
