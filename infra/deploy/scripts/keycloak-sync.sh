#!/usr/bin/env bash
# Makes the realm "rozklad" match infra/deploy/.env. deploy.sh runs it after every start.
#   - rozklad-web: base URL, redirect URIs, web origins and post-logout URIs for https://DOMAIN
#     (and EXTRA_WEB_ORIGIN for the Netlify-hosted client);
#   - secrets of the confidential clients rozklad-core-admin, rozklad-notification, rozklad-importer;
#   - the user profile of infra/keycloak/user-profile.json (e-mail and first name optional).
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
IFS= read -r user_profile

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

# Keycloak 26.8 accepts admin REST calls only with the audience of the scope kc-admin-api: without it
# the invites of core get 401 from /admin/realms/rozklad/users. Realms imported earlier lack it.
admin_scope=$(kc get client-scopes -r rozklad --fields id,name --format csv --noquotes | sed -n "s/^\([^,]*\),kc-admin-api$/\1/p")
[[ -n $admin_scope ]] || { echo "client scope kc-admin-api not found in the realm rozklad" >&2; exit 1; }
kc update "clients/$(client_uuid rozklad-core-admin)/default-client-scopes/$admin_scope" -r rozklad
echo "rozklad-core-admin: default client scope kc-admin-api"

# E-mail and first name optional: an invited teacher (most have initials only) signs in right after
# accepting the invite instead of stopping at «Update account information».
printf "%s" "$user_profile" | kc update users/profile -r rozklad -f - >/dev/null
echo "user profile: e-mail and first name optional"
'

profile_file="$here/../../keycloak/user-profile.json"
[[ -f $profile_file ]] || die "missing $profile_file"
log "Keycloak: syncing clients with $(basename "$ROZKLAD_ENV_FILE")"
printf '%s\n' "$origin" "$extra" "${secrets[@]}" "$(tr -d '\n' < "$profile_file")" | kc_run "$code"
