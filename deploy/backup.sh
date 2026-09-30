#!/usr/bin/env bash
# Dump the octo database to a timestamped, gzipped archive and prune old ones.
#
#   PGPASSWORD=… ./backup.sh
#
# Env: POSTGRES_HOST (default localhost), POSTGRES_PORT (5432), POSTGRES_DB
# (octo), POSTGRES_USER (octo), BACKUP_DIR (./backups), KEEP_DAYS (14).
# PGHOST/PGPORT-style vars are deliberately not read — this script only knows
# the names deploy/README.md publishes.
set -euo pipefail

HOST="${POSTGRES_HOST:-localhost}"
PORT="${POSTGRES_PORT:-5432}"
DB="${POSTGRES_DB:-octo}"
USER="${POSTGRES_USER:-octo}"
DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"

mkdir -p "$DIR"
OUT="$DIR/octo-${DB}-$(date -u +%Y%m%dT%H%M%SZ).dump.gz"

# custom format + gzip: restorable with pg_restore after decompression.
pg_dump -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" --format=plain --no-owner | gzip >"$OUT"
echo "wrote $OUT"

find "$DIR" -name "octo-${DB}-*.dump.gz" -mtime "+$KEEP_DAYS" -delete
