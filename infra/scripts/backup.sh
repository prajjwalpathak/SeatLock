#!/usr/bin/env bash
# Nightly Postgres backup (W8·D2, docs/deploy.md section 5b). Runs on the VM from systemd (seatlock-backup.timer).
#   1. pg_dump -Fc inside the postgres container (consistent snapshot, compressed, per-table restore possible)
#   2. verify: pg_restore --list must read the whole table of contents
#   3. keep the newest 7 dumps in ~/backups (mode 700/600)
#   4. upload to Azure Blob: daily/<file> every night, monthly/<file> on the 1st (IST); the SAS token is
#      write-only (create + write), so this VM can't read or delete old backups; daily/ expires after 30 days
#      (lifecycle rule in the portal), monthly/ is kept
#   5. one log line (journalctl -u seatlock-backup)
# Manual run: bash ~/seatlock/infra/scripts/backup.sh        (FORCE_MONTHLY=1 also uploads a monthly copy)
set -euo pipefail

ROOT="${SEATLOCK_ROOT:-$HOME/seatlock}"
ENV_FILE="$ROOT/infra/.env.prod"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
KEEP="${BACKUP_KEEP:-7}"

# Read single keys from .env.prod (never "source" it: values may contain shell characters).
get() { grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true; }
dc() { docker compose --env-file "$ENV_FILE" -f "$ROOT/infra/docker-compose.prod.yml" "$@"; }

DB_USER="$(get POSTGRES_USER)"; DB_USER="${DB_USER:-seatlock}"
DB_NAME="$(get POSTGRES_DB)";   DB_NAME="${DB_NAME:-seatlock}"
BLOB_URL="$(get BACKUP_BLOB_URL)"; BLOB_URL="${BLOB_URL%/}"   # https://<account>.blob.core.windows.net/pg-backups
SAS="$(get BACKUP_SAS_TOKEN)";     SAS="${SAS#\?}"            # with or without the leading "?"

stamp="$(date -u +%Y-%m-%dT%H%M%SZ)"
name="seatlock-$stamp.dump"
file="$BACKUP_DIR/$name"
start="$(date +%s)"

trap 'rm -f "$file.part"; echo "backup FAILED at line $LINENO ($name)" >&2' ERR

mkdir -p "$BACKUP_DIR" && chmod 700 "$BACKUP_DIR"
dc exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$file.part"
dc exec -T postgres pg_restore --list < "$file.part" > /dev/null      # a truncated/empty dump fails here
mv "$file.part" "$file" && chmod 600 "$file"
size="$(stat -c %s "$file")"

# The SAS goes to curl through stdin (--config -), so it never appears in the process list or the journal.
upload() {
  printf 'url = "%s/%s?%s"\n' "$BLOB_URL" "$1" "$SAS" | curl --config - --fail --silent --show-error \
    --max-time 300 -X PUT -H "x-ms-blob-type: BlockBlob" -H "x-ms-version: 2021-08-06" \
    --data-binary @"$file" -o /dev/null
}
uploaded="skipped (BACKUP_BLOB_URL / BACKUP_SAS_TOKEN not set)"
if [ -n "$BLOB_URL" ] && [ -n "$SAS" ]; then
  upload "daily/$name"; uploaded="daily"
  if [ "$(TZ=Asia/Kolkata date +%d)" = "01" ] || [ "${FORCE_MONTHLY:-0}" = "1" ]; then
    upload "monthly/$name"; uploaded="daily+monthly"
  fi
fi

# Local rotation: newest $KEEP stay.
ls -1t "$BACKUP_DIR"/seatlock-*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

echo "backup OK: $name, $size bytes, $(( $(date +%s) - start )) s, upload: $uploaded, local copies: $(ls -1 "$BACKUP_DIR"/seatlock-*.dump | wc -l)"
