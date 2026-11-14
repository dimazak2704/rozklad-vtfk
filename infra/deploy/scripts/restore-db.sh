#!/usr/bin/env bash
# Restores a dump made by backup-db.sh. DESTRUCTIVE: every database is replaced by the dump.
#   infra/deploy/scripts/restore-db.sh /var/backups/rozklad/rozklad-20261107-031500.sql.gz --yes
#
# Steps: check that the running images are on this server (infra/deploy/.env.images, written by
# deploy.sh), stop everything except PostgreSQL, load the dump, set the service passwords from the
# current infra/deploy/.env again (the dump carries the old ones), start the same images again. auth
# applies the client secrets and the demo accounts of .env when it starts.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
require_server

dump=${1:-}
[[ -n $dump && -f $dump ]] || die "usage: restore-db.sh <rozklad-YYYYmmdd-HHMMSS.sql.gz> --yes"
[[ $dump != *.age ]] || die "$dump is encrypted. Decrypt it on your PC, where the private key is, and copy the plain
       dump here (docs/DEPLOY.md, section 12):
         age -d -i rozklad-backup.key $(basename "$dump") | ssh <user>@<server> 'umask 077; cat >/tmp/restore.sql.gz'"
[[ ${2:-} == --yes ]] || die "this replaces every database with $dump; run again with --yes to confirm"
gzip -t "$dump" || die "$dump is not a valid gzip file"
# nothing is stopped yet: a missing image would otherwise leave the stack down after the restore
require_images

log "Stopping the services (PostgreSQL keeps running)"
compose stop caddy web gateway core scheduler importer notification auth
compose up -d --no-build --wait postgres

log "Loading $dump"
# Two errors about the role "postgres" are expected: the dump cannot drop or create the user
# it is connected as. Everything else must load without errors.
gunzip -c "$dump" | compose exec -T postgres psql --quiet --no-psqlrc --username postgres --dbname postgres >/dev/null

log "Setting the service passwords from $(basename "$ROZKLAD_ENV_FILE")"
compose exec -T postgres bash /docker-entrypoint-initdb.d/20-prod-passwords.sh

log "Starting the stack"
compose up -d --no-build --wait --wait-timeout "${DEPLOY_WAIT_TIMEOUT:-900}"
ok "restored from $dump"
