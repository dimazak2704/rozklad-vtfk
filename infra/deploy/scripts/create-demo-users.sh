#!/usr/bin/env bash
# Creates or updates the demo accounts for the defense in the realm "rozklad":
#   admin      ADMIN    password DEMO_ADMIN_PASSWORD
#   krentsin   TEACHER  password DEMO_TEACHER_PASSWORD
#   nepyivoda  TEACHER  password DEMO_TEACHER_PASSWORD
# The accounts get the same ids as in the development realm (keycloak/demo-users.json), so demo
# data that links teachers to those ids works on the server as well. Nothing else is linked or
# changed. An empty password disables the account. Idempotent; deploy.sh runs it after every start.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
require_server

admin_pw=$(env_get DEMO_ADMIN_PASSWORD)
teacher_pw=$(env_get DEMO_TEACHER_PASSWORD)
for value in "$admin_pw" "$teacher_pw"; do
  [[ $value != change-me ]] || die "demo passwords are still 'change-me': run infra/deploy/scripts/gen-secrets.sh"
  [[ $value != *$'\n'* ]] || die "a demo password contains a line break"
done

# shellcheck disable=SC2016 # expanded inside the container
code='
IFS= read -r admin_pw
IFS= read -r teacher_pw
users_json="/tmp/demo-users-$$.json"
tmp_files+=("$users_json")
cat >"$users_json"

# creates the missing accounts with their fixed ids; existing ones are left as they are
kc create partialImport -r rozklad -s ifResourceExists=SKIP -f "$users_json" >/dev/null

for entry in admin:ADMIN:admin_pw krentsin:TEACHER:teacher_pw nepyivoda:TEACHER:teacher_pw; do
  IFS=: read -r username role password_var <<<"$entry"
  password=${!password_var}
  id=$(kc get users -r rozklad -q "username=$username" -q exact=true --fields id --format csv --noquotes)
  [[ -n $id ]] || { echo "user $username was not created" >&2; exit 1; }
  if [[ -z $password ]]; then
    kc update "users/$id" -r rozklad -s enabled=false
    echo "$username: disabled (no password in .env)"
    continue
  fi
  kc update "users/$id" -r rozklad -s enabled=true
  kc set-password -r rozklad --userid "$id" --new-password "$password"
  kc add-roles -r rozklad --uid "$id" --rolename "$role"
  echo "$username: ready ($role)"
done
'

log "Keycloak: demo accounts"
# passwords and the account list travel through stdin, never through a command line
{
  printf '%s\n%s\n' "$admin_pw" "$teacher_pw"
  cat "$ROZKLAD_DEPLOY_DIR/keycloak/demo-users.json"
} | kc_run "$code"
