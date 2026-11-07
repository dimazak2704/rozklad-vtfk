#!/usr/bin/env bash
# Dumps the whole PostgreSQL cluster (all service databases, Keycloak, roles) into
# $BACKUP_DIR/rozklad-YYYYmmdd-HHMMSS.sql.gz and deletes dumps older than BACKUP_KEEP_DAYS days.
#   infra/deploy/scripts/backup-db.sh
#
# Daily at 03:15 (crontab -e, as the user that runs docker; adjust the path to your clone):
#   15 3 * * * $HOME/rozklad/infra/deploy/scripts/backup-db.sh >>$HOME/rozklad-backup.log 2>&1
#
# Restore: infra/deploy/scripts/restore-db.sh <file> --yes. A dump is useless without the matching
# infra/deploy/.env: keep that file in a password manager, not next to the dumps. A dump holds every
# account, Keycloak's client secrets and password hashes: encrypt it before it leaves this server
# (docs/DEPLOY.md, section 12).
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
require_server

backup_dir=$(env_get BACKUP_DIR /var/backups/rozklad)
keep_days=$(env_get BACKUP_KEEP_DAYS 14)
[[ $keep_days =~ ^[0-9]+$ ]] || die "BACKUP_KEEP_DAYS must be a number of days, got '$keep_days'"

umask 077
mkdir -p "$backup_dir" 2>/dev/null ||
  die "cannot create $backup_dir: sudo install -d -m 700 -o \"\$USER\" \"$backup_dir\""
[[ -w $backup_dir ]] || die "$backup_dir is not writable: sudo chown \"\$USER\" \"$backup_dir\""

file="$backup_dir/rozklad-$(date +%Y%m%d-%H%M%S).sql.gz"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

log "Dumping all databases to $file"
# --clean --if-exists: the dump drops and recreates every database when it is restored
compose exec -T postgres pg_dumpall --username postgres --clean --if-exists | gzip -9 >"$partial"
gzip -t "$partial"
mv "$partial" "$file"
trap - EXIT

find "$backup_dir" -maxdepth 1 -type f -name 'rozklad-*.sql.gz' -mtime +"$keep_days" -print -delete |
  sed 's/^/removed old dump: /'
ok "$(du -h "$file" | cut -f1) $file"
