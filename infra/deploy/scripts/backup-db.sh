#!/usr/bin/env bash
# Dumps the whole PostgreSQL cluster (all service databases incl. auth, roles) into
# $BACKUP_DIR/rozklad-YYYYmmdd-HHMMSS.sql.gz and deletes dumps older than BACKUP_KEEP_DAYS days.
#   infra/deploy/scripts/backup-db.sh
#
# Daily at 03:15 (crontab -e, as the user that runs docker; adjust the path to your clone):
#   15 3 * * * $HOME/rozklad/infra/deploy/scripts/backup-db.sh >>$HOME/rozklad-backup.log 2>&1
#
# A dump holds every account with its password hash and the sign-in sessions of auth. Optional
# settings in infra/deploy/.env (docs/DEPLOY.md, section 12):
#   BACKUP_AGE_RECIPIENT  an age public key (age1...): the dump is encrypted here, as
#                         rozklad-*.sql.gz.age; only the private key, kept off this server, reads it
#   BACKUP_RCLONE_REMOTE  rclone destination (remote:path): every new dump is copied there. Needs
#                         BACKUP_AGE_RECIPIENT: a plain dump never leaves this server
#   BACKUP_PING_URL       fetched after a fully successful run (dead-man switch such as healthchecks.io);
#                         a failed run does not ping, so the service alerts
#
# Restore: infra/deploy/scripts/restore-db.sh <file> --yes (an .age file is decrypted first). A dump is
# useless without the matching infra/deploy/.env: keep that file in a password manager, not next to
# the dumps.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
require_server

backup_dir=$(env_get BACKUP_DIR /var/backups/rozklad)
keep_days=$(env_get BACKUP_KEEP_DAYS 14)
recipient=$(env_get BACKUP_AGE_RECIPIENT)
remote=$(env_get BACKUP_RCLONE_REMOTE)
ping_url=$(env_get BACKUP_PING_URL)
[[ $keep_days =~ ^[0-9]+$ ]] || die "BACKUP_KEEP_DAYS must be a number of days, got '$keep_days'"

# every setting is checked before anything is dumped
if [[ -n $recipient ]]; then
  [[ $recipient =~ ^age1[02-9ac-hj-np-z]{58}$ ]] ||
    die "BACKUP_AGE_RECIPIENT must be an age public key (age1 and 58 more characters), got '$recipient'"
  command -v age >/dev/null || die "BACKUP_AGE_RECIPIENT is set but age is not installed: sudo apt-get install -y age"
fi
if [[ -n $remote ]]; then
  [[ -n $recipient ]] ||
    die "BACKUP_RCLONE_REMOTE needs BACKUP_AGE_RECIPIENT: a plain dump never leaves this server"
  command -v rclone >/dev/null || die "BACKUP_RCLONE_REMOTE is set but rclone is not installed (docs/DEPLOY.md, section 12)"
fi

umask 077
mkdir -p "$backup_dir" 2>/dev/null ||
  die "cannot create $backup_dir: sudo install -d -m 700 -o \"\$USER\" \"$backup_dir\""
[[ -w $backup_dir ]] || die "$backup_dir is not writable: sudo chown \"\$USER\" \"$backup_dir\""

file="$backup_dir/rozklad-$(date +%Y%m%d-%H%M%S).sql.gz"
[[ -z $recipient ]] || file="$file.age"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

# encrypt: the plain dump only ever exists in the pipe
encrypt() {
  if [[ -n $recipient ]]; then age -r "$recipient"; else cat; fi
}

log "Dumping all databases to $file"
# --clean --if-exists: the dump drops and recreates every database when it is restored
compose exec -T postgres pg_dumpall --username postgres --clean --if-exists | gzip -9 | encrypt >"$partial"
if [[ -n $recipient ]]; then
  [[ $(head -n 1 "$partial") == age-encryption.org/v1 ]] || die "age wrote no encrypted file"
else
  gzip -t "$partial"
fi
mv "$partial" "$file"
trap - EXIT

find "$backup_dir" -maxdepth 1 -type f \( -name 'rozklad-*.sql.gz' -o -name 'rozklad-*.sql.gz.age' \) \
  -mtime +"$keep_days" -print -delete | sed 's/^/removed old dump: /'
ok "$(du -h "$file" | cut -f1) $file"

if [[ -n $remote ]]; then
  # copy only: old copies are removed by a lifecycle rule of the storage, so a key that cannot delete
  # is enough here (a compromised server then cannot wipe the off-site copies)
  rclone copy "$file" "$remote" || die "the copy to $remote failed; the dump stays in $backup_dir"
  ok "copied to $remote"
fi

if [[ -n $ping_url ]]; then
  curl -fsS -m 10 --retry 3 -o /dev/null "$ping_url" || warn "the ping to BACKUP_PING_URL failed"
fi
