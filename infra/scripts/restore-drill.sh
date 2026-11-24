#!/usr/bin/env bash
# Restore drill (W8·D2, docs/deploy.md section 5b): restores the newest local dump into a SCRATCH database
# (seatlock_restore_test, never the live one), compares row counts with live, times it, drops the scratch DB.
# Usage on the VM: bash ~/seatlock/infra/scripts/restore-drill.sh [path/to/file.dump]
set -euo pipefail

ROOT="${SEATLOCK_ROOT:-$HOME/seatlock}"
ENV_FILE="$ROOT/infra/.env.prod"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
SCRATCH=seatlock_restore_test

get() { grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true; }
dc() { docker compose --env-file "$ENV_FILE" -f "$ROOT/infra/docker-compose.prod.yml" "$@"; }
DB_USER="$(get POSTGRES_USER)"; DB_USER="${DB_USER:-seatlock}"
DB_NAME="$(get POSTGRES_DB)";   DB_NAME="${DB_NAME:-seatlock}"
psql_on() { dc exec -T postgres psql -U "$DB_USER" -d "$1" -v ON_ERROR_STOP=1 -tA "${@:2}"; }

dump="${1:-$(ls -1t "$BACKUP_DIR"/seatlock-*.dump | head -n 1)}"
echo "Restoring $(basename "$dump") ($(stat -c %s "$dump") bytes) into $SCRATCH"

psql_on postgres -c "DROP DATABASE IF EXISTS $SCRATCH" -c "CREATE DATABASE $SCRATCH" > /dev/null
trap 'psql_on postgres -c "DROP DATABASE IF EXISTS '"$SCRATCH"'" > /dev/null || true' EXIT

start_ms="$(( $(date +%s%N) / 1000000 ))"
dc exec -T postgres pg_restore -U "$DB_USER" -d "$SCRATCH" --no-owner --exit-on-error < "$dump"
elapsed_ms="$(( $(date +%s%N) / 1000000 - start_ms ))"

printf '%-24s %10s %10s\n' table live restored
for t in users events shows show_seats bookings booking_seats payments refunds webhook_events ai_requests policy_chunks flyway_schema_history; do
  live="$(psql_on "$DB_NAME" -c "SELECT count(*) FROM $t")"
  restored="$(psql_on "$SCRATCH" -c "SELECT count(*) FROM $t")"
  printf '%-24s %10s %10s\n' "$t" "$live" "$restored"
done
echo "Restore took ${elapsed_ms} ms. Live can be ahead of the dump by writes since it was taken; restored must never be 0 where live isn't."
