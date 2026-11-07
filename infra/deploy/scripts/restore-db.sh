#!/usr/bin/env bash
# Restores a dump made by backup-db.sh. DESTRUCTIVE: every database is replaced by the dump.
#   infra/deploy/scripts/restore-db.sh /var/backups/rozklad/rozklad-20261107-031500.sql.gz --yes
#
# Steps: check that the running images are on this server (infra/deploy/.env.images, written by
# deploy.sh), stop everything except PostgreSQL, load the dump, set the service passwords from the
# current infra/deploy/.env again (the dump carries the old ones), start the same images again, sync
# Keycloak clients and demo accounts with .env.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
require_server

dump=${1:-}
[[ -n $dump && -f $dump ]] || die "usage: restore-db.sh <rozklad-YYYYmmdd-HHMMSS.sql.gz> --yes"
[[ ${2:-} == --yes ]] || die "this replaces every database with $dump; run again with --yes to confirm"
gzip -t "$dump" || die "$dump is not a valid gzip file"
# nothing is stopped yet: a missing image would otherwise leave the stack down after the restore
require_images

log "Stopping the services (PostgreSQL keeps running)"
compose stop caddy web gateway core scheduler importer notification keycloak
compose up -d --no-build --wait postgres

log "Loading $dump"
# Two errors about the role "postgres" are expected: the dump cannot drop or create the user
# it is connected as. Everything else must load without errors.
gunzip -c "$dump" | compose exec -T postgres psql --quiet --no-psqlrc --username postgres --dbname postgres >/dev/null

log "Setting the service passwords from $(basename "$ROZKLAD_ENV_FILE")"
compose exec -T postgres bash /docker-entrypoint-initdb.d/20-prod-passwords.sh

log "Starting the stack"
compose up -d --no-build --wait --wait-timeout "${DEPLOY_WAIT_TIMEOUT:-900}"
bash "$here/keycloak-sync.sh"
# always: the dump may bring back demo accounts that .env disables (empty password)
bash "$here/create-demo-users.sh"
ok "restored from $dump"
