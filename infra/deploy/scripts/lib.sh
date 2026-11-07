# Shared helpers of the deployment scripts. Source this file, do not run it.
# shellcheck shell=bash

ROZKLAD_DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROZKLAD_REPO_DIR="$(cd "$ROZKLAD_DEPLOY_DIR/../.." && pwd)"
ROZKLAD_ENV_FILE="${ROZKLAD_ENV_FILE:-$ROZKLAD_DEPLOY_DIR/.env}"

if [[ -t 1 ]]; then
  _c_info=$'\033[1;34m' _c_ok=$'\033[1;32m' _c_warn=$'\033[1;33m' _c_err=$'\033[1;31m' _c_off=$'\033[0m'
else
  _c_info='' _c_ok='' _c_warn='' _c_err='' _c_off=''
fi

log()  { printf '%s==>%s %s\n' "$_c_info" "$_c_off" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$_c_ok" "$_c_off" "$*"; }
warn() { printf '%sWARN%s %s\n' "$_c_warn" "$_c_off" "$*" >&2; }
die()  { printf '%sERROR%s %s\n' "$_c_err" "$_c_off" "$*" >&2; exit 1; }

# The scripts manage the PRODUCTION stack. On the development PC the compose project "rozklad" is
# the local stack, and running them there would recreate it with production settings.
require_server() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      if [[ "${ROZKLAD_ALLOW_LOCAL:-0}" != 1 || -z "${COMPOSE_PROJECT_NAME:-}" || "${COMPOSE_PROJECT_NAME}" == rozklad ]]; then
        die "these scripts manage the production stack on the Linux server; here they would replace the
       development containers of the project 'rozklad'. For a local experiment set
       ROZKLAD_ALLOW_LOCAL=1 and COMPOSE_PROJECT_NAME=<another name>."
      fi
      ;;
  esac
}

# env_get KEY [default]: the value of KEY like docker compose sees it. The process environment
# wins over the env file, the file is parsed (never executed), empty means "use the default".
env_get() {
  local key=$1 default=${2-} line value
  if [[ -n "${!key+set}" ]]; then
    printf '%s' "${!key}"
    return
  fi
  line=''
  if [[ -f "$ROZKLAD_ENV_FILE" ]]; then
    line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" "$ROZKLAD_ENV_FILE" | tail -n 1 || true)
  fi
  value=${line#*=}
  value=${value%$'\r'}
  if [[ $value =~ ^\"(.*)\"$ || $value =~ ^\'(.*)\'$ ]]; then
    value=${BASH_REMATCH[1]}
  fi
  printf '%s' "${value:-$default}"
}

# docker compose with the base file, the production overlay and the production env file
compose() {
  docker compose \
    -f "$ROZKLAD_REPO_DIR/infra/docker-compose.yml" \
    -f "$ROZKLAD_DEPLOY_DIR/compose.prod.yml" \
    --env-file "$ROZKLAD_ENV_FILE" \
    --profile app \
    "$@"
}

# kc_run CODE: runs bash CODE inside the keycloak container. CODE can call `kc ...` (kcadm.sh
# logged in as the bootstrap admin of the master realm) and add temporary files to `tmp_files`.
# Pass secrets through stdin: the container's stdin is the caller's stdin.
kc_run() {
  local prelude
  # shellcheck disable=SC2016 # expanded inside the container, not here
  prelude='set -euo pipefail
cfg="/tmp/kcadm-$$.config"
tmp_files=("$cfg")
trap '\''rm -f "${tmp_files[@]}"'\'' EXIT
kc() { /opt/keycloak/bin/kcadm.sh "$@" --config "$cfg"; }
if ! login_out=$(kc config credentials --server http://localhost:8080 --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" 2>&1); then
  printf "%s\n" "$login_out" >&2
  echo "kcadm: cannot log in as the bootstrap admin. KEYCLOAK_ADMIN_USER/KEYCLOAK_ADMIN_PASSWORD are applied on the very first start only; if you changed them later, put the old values back." >&2
  exit 1
fi
'
  compose exec -T keycloak bash -c "$prelude$1"
}

# is_origin VALUE: https://host[:port] without a path (the shape Keycloak and CORS expect)
is_origin() {
  [[ $1 =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$ ]]
}
