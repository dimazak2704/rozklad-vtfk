#!/usr/bin/env bash
# Makes the realm "rozklad" match infra/deploy/.env. deploy.sh runs it after every start.
#   - rozklad-web: base URL, redirect URIs, web origins and post-logout URIs for https://DOMAIN
#     (and EXTRA_WEB_ORIGIN for the Netlify-hosted client);
#   - secrets of the confidential clients rozklad-core-admin, rozklad-notification, rozklad-importer.
# The realm file is imported only on the very first start, so without this a new DOMAIN or a
# changed secret in .env would never reach Keycloak. Idempotent.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"
require_server

domain=$(env_get DOMAIN)
origin="https://$domain"
extra=$(env_get EXTRA_WEB_ORIGIN)
extra=${extra%/}
extra=${extra:-$origin}
is_origin "$origin" || die "DOMAIN must be a host name, got '$domain'"
is_origin "$extra" || die "EXTRA_WEB_ORIGIN must look like https://name.netlify.app, got '$extra'"

secrets=()
for key in CORE_ADMIN_CLIENT_SECRET NOTIFICATION_CLIENT_SECRET IMPORTER_CLIENT_SECRET; do
  value=$(env_get "$key")
  # the value is sent as a JSON string: keep it to characters that need no escaping
  [[ $value =~ ^[A-Za-z0-9._~+/=-]{16,}$ ]] || die "$key must be at least 16 characters of [A-Za-z0-9._~+/=-] (gen-secrets.sh makes such values)"
  secrets+=("$value")
done

# shellcheck disable=SC2016 # expanded inside the container
code='
IFS= read -r origin
IFS= read -r extra
IFS= read -r core_secret
IFS= read -r notification_secret
IFS= read -r importer_secret

client_uuid() {
  local id
  id=$(kc get clients -r rozklad -q "clientId=$1" --fields id --format csv --noquotes)
  [[ -n $id ]] || { echo "client $1 not found in the realm rozklad (was the realm imported?)" >&2; exit 1; }
  printf "%s" "$id"
}

web=$(client_uuid rozklad-web)
kc update "clients/$web" -r rozklad \
  -s "baseUrl=\"$origin/\"" \
  -s "redirectUris=[\"$origin/*\",\"$extra/*\"]" \
  -s "webOrigins=[\"$origin\",\"$extra\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=\"$origin/*##$extra/*\""
if [[ $extra != "$origin" ]]; then
  echo "rozklad-web: $origin and $extra"
else
  echo "rozklad-web: $origin"
fi

for pair in "rozklad-core-admin:$core_secret" "rozklad-notification:$notification_secret" "rozklad-importer:$importer_secret"; do
  name=${pair%%:*}
  kc update "clients/$(client_uuid "$name")" -r rozklad -s "secret=\"${pair#*:}\""
  echo "$name: secret from .env"
done
'

log "Keycloak: syncing clients with $(basename "$ROZKLAD_ENV_FILE")"
printf '%s\n' "$origin" "$extra" "${secrets[@]}" | kc_run "$code"
