#!/usr/bin/env bash
# Creates infra/deploy/.env from .env.example (if it does not exist yet) and fills in random secrets.
#   infra/deploy/scripts/gen-secrets.sh [path/to/.env]
#
# Never overwrites a value you have set. It fills:
#   - required secrets (database, RabbitMQ, Redis, Keycloak admin, client secrets) that are missing,
#     empty or "change-me";
#   - optional ones (demo passwords, Telegram webhook secret) that are missing or "change-me";
#     an empty optional value means "off" and is kept.
# Prints only the names of the generated keys, never the values. The file gets mode 600.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

env_file="${1:-$ROZKLAD_ENV_FILE}"
example="$ROZKLAD_DEPLOY_DIR/.env.example"
placeholder='change-me'

required=(
  POSTGRES_PASSWORD CORE_DB_PASSWORD SCHEDULER_DB_PASSWORD IMPORTER_DB_PASSWORD
  NOTIFICATION_DB_PASSWORD KEYCLOAK_DB_PASSWORD RABBIT_PASSWORD REDIS_PASSWORD
  KEYCLOAK_ADMIN_PASSWORD CORE_ADMIN_CLIENT_SECRET NOTIFICATION_CLIENT_SECRET IMPORTER_CLIENT_SECRET
)
optional=(DEMO_ADMIN_PASSWORD DEMO_TEACHER_PASSWORD TELEGRAM_WEBHOOK_SECRET)

# 32 characters from [A-Za-z0-9]: safe in URLs, JDBC, YAML and shell without quoting
random_secret() {
  local value=''
  while ((${#value} < 32)); do
    value+=$(head -c 48 /dev/urandom | base64 | LC_ALL=C tr -dc 'A-Za-z0-9')
  done
  printf '%s' "${value:0:32}"
}

# xxxx-xxxx-xxxx from [a-z0-9]: easy to type at the defense, about 62 bits
random_demo_password() {
  local value=''
  while ((${#value} < 12)); do
    value+=$(head -c 48 /dev/urandom | base64 | LC_ALL=C tr -dc 'a-z0-9')
  done
  printf '%s-%s-%s' "${value:0:4}" "${value:4:4}" "${value:8:4}"
}

new_value() {
  case $1 in
    DEMO_*) random_demo_password ;;
    *) random_secret ;;
  esac
}

contains() {
  local needle=$1 item
  shift
  for item in "$@"; do [[ $item == "$needle" ]] && return 0; done
  return 1
}

# needs_value KEY VALUE: should gen-secrets replace this value?
needs_value() {
  local key=$1 value=$2
  if contains "$key" "${required[@]}"; then
    [[ -z $value || $value == "$placeholder" ]]
  elif contains "$key" "${optional[@]}"; then
    [[ $value == "$placeholder" ]]
  else
    return 1
  fi
}

umask 077
if [[ ! -f $env_file ]]; then
  [[ -f $example ]] || die "$example not found"
  cp "$example" "$env_file"
  log "created $env_file from .env.example"
fi

tmp=$(mktemp "$env_file.XXXXXX")
trap 'rm -f "$tmp"' EXIT

declare -A seen=()
generated=()
while IFS= read -r line || [[ -n $line ]]; do
  line=${line%$'\r'}
  if [[ $line =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    seen[$key]=1
    if needs_value "$key" "$value"; then
      line="$key=$(new_value "$key")"
      generated+=("$key")
    fi
  fi
  printf '%s\n' "$line" >>"$tmp"
done <"$env_file"

added_header=0
for key in "${required[@]}" "${optional[@]}"; do
  if [[ -z ${seen[$key]:-} ]]; then
    if ((added_header == 0)); then
      printf '\n# ---- added by gen-secrets.sh ----\n' >>"$tmp"
      added_header=1
    fi
    printf '%s=%s\n' "$key" "$(new_value "$key")" >>"$tmp"
    generated+=("$key")
  fi
done

mv "$tmp" "$env_file"
trap - EXIT
chmod 600 "$env_file"

if ((${#generated[@]})); then
  log "generated: ${generated[*]}"
else
  log "nothing to generate: every secret already has a value"
fi

todo=()
domain=$(ROZKLAD_ENV_FILE=$env_file env_get DOMAIN)
email=$(ROZKLAD_ENV_FILE=$env_file env_get ACME_EMAIL)
[[ -z $domain || $domain == *example.com ]] && todo+=("DOMAIN")
[[ -z $email || $email == *@example.com ]] && todo+=("ACME_EMAIL")
[[ -z $(ROZKLAD_ENV_FILE=$env_file env_get TELEGRAM_BOT_TOKEN) ]] && todo+=("TELEGRAM_BOT_TOKEN (optional)")
[[ -z $(ROZKLAD_ENV_FILE=$env_file env_get ANTHROPIC_API_KEY) ]] && todo+=("ANTHROPIC_API_KEY (optional)")
if ((${#todo[@]})); then
  log "fill in by hand in $env_file: ${todo[*]}"
fi
