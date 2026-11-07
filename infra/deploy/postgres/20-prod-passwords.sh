#!/usr/bin/env bash
# Production passwords for the service users that infra/postgres/init.sql (10-init.sql) creates with
# development passwords. The postgres image runs this once, when the data volume is initialised;
# restore-db.sh runs it again after a restore. Values come from the container environment
# (compose.prod.yml <- infra/deploy/.env).
#
# The image entrypoint may source this file instead of executing it, so it does not change shell
# options and exits only on errors.

for _rozklad_var in CORE_DB_PASSWORD SCHEDULER_DB_PASSWORD IMPORTER_DB_PASSWORD NOTIFICATION_DB_PASSWORD KEYCLOAK_DB_PASSWORD; do
  if [ -z "${!_rozklad_var:-}" ]; then
    echo "20-prod-passwords: $_rozklad_var is empty" >&2
    exit 1
  fi
done
unset _rozklad_var

# \getenv reads the values inside psql, so they never appear on a command line
psql -v ON_ERROR_STOP=1 --no-psqlrc --username "${POSTGRES_USER:-postgres}" --dbname postgres <<'SQL' || { echo "20-prod-passwords: failed" >&2; exit 1; }
\getenv core_pw CORE_DB_PASSWORD
\getenv scheduler_pw SCHEDULER_DB_PASSWORD
\getenv importer_pw IMPORTER_DB_PASSWORD
\getenv notification_pw NOTIFICATION_DB_PASSWORD
\getenv keycloak_pw KEYCLOAK_DB_PASSWORD
ALTER ROLE core WITH PASSWORD :'core_pw';
ALTER ROLE scheduler WITH PASSWORD :'scheduler_pw';
ALTER ROLE importer WITH PASSWORD :'importer_pw';
ALTER ROLE notification WITH PASSWORD :'notification_pw';
ALTER ROLE keycloak WITH PASSWORD :'keycloak_pw';
SQL
echo "20-prod-passwords: production passwords set for core, scheduler, importer, notification, keycloak"
