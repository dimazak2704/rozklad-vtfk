#!/usr/bin/env bash
# Creates infra/deploy/.env from .env.example (if it does not exist yet) and fills in random secrets.
#   infra/deploy/scripts/gen-secrets.sh [path/to/.env]
#
# Never overwrites a value you have set. It fills:
#   - required secrets (database, RabbitMQ, Redis, client secrets of the sign-in service) that are
#     missing, empty or "change-me";
#   - optional ones (first admin password, demo password, Telegram webhook secret) that are missing or
#     "change-me"; an empty optional value means "off" and is kept.
# It also creates the token signing key of auth, infra/deploy/secrets/auth-signing-key.pem (RSA 2048,
# PKCS#8), when it is missing, and then gives AUTH_SIGNING_KEY_ID a new value. The directory gets mode
# 700, the key 644 (the container user of auth has another uid and reads the bind-mounted file).
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
  NOTIFICATION_DB_PASSWORD AUTH_DB_PASSWORD RABBIT_PASSWORD REDIS_PASSWORD
  NOTIFICATION_CLIENT_SECRET IMPORTER_CLIENT_SECRET CORE_AUTH_CLIENT_SECRET AUTH_SIGNING_KEY_ID
)
optional=(AUTH_BOOTSTRAP_ADMIN_PASSWORD DEMO_TEACHER_PASSWORD TELEGRAM_WEBHOOK_SECRET)
signing_key="$ROZKLAD_DEPLOY_DIR/secrets/auth-signing-key.pem"

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

# xxxx-xxxx-xxxx-xxxx from [a-z0-9]: typed once at the first sign-in, about 82 bits
random_admin_password() {
  local value=''
  while ((${#value} < 16)); do
    value+=$(head -c 48 /dev/urandom | base64 | LC_ALL=C tr -dc 'a-z0-9')
  done
  printf '%s-%s-%s-%s' "${value:0:4}" "${value:4:4}" "${value:8:4}" "${value:12:4}"
}

new_value() {
  case $1 in
    DEMO_*) random_demo_password ;;
    AUTH_BOOTSTRAP_ADMIN_PASSWORD) random_admin_password ;;
    # the kid of the signing key: its date and a random suffix, e.g. 2026-11-12-k3f9
    AUTH_SIGNING_KEY_ID) printf '%s-%s' "$(date -u +%Y-%m-%d)" "$(random_secret | LC_ALL=C tr -dc 'a-z0-9' | head -c 4)" ;;
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
  # a new signing key needs a new kid, or the services would keep the old key cached under the old one
  if [[ $key == AUTH_SIGNING_KEY_ID ]] && ((new_key)); then
    return 0
  fi
  if contains "$key" "${required[@]}"; then
    [[ -z $value || $value == "$placeholder" ]]
  elif contains "$key" "${optional[@]}"; then
    [[ $value == "$placeholder" ]]
  else
    return 1
  fi
}

umask 077
new_key=0
if [[ ! -f $signing_key ]]; then
  command -v openssl >/dev/null || die "openssl is needed for the signing key: sudo apt-get install openssl"
  install -d -m 700 "$(dirname "$signing_key")"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$signing_key.tmp" 2>/dev/null
  chmod 644 "$signing_key.tmp"
  mv "$signing_key.tmp" "$signing_key"
  new_key=1
  log "created the signing key $signing_key"
fi

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
