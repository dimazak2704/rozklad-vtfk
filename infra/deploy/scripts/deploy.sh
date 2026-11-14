#!/usr/bin/env bash
# Deploys or updates «Розклад ВТФК» on the VPS. One command for the first start and for updates.
#
#   infra/deploy/scripts/deploy.sh                 git pull, build or pull the images, start, check
#   infra/deploy/scripts/deploy.sh --no-pull       deploy the working tree as it is
#   infra/deploy/scripts/deploy.sh --pull-images   use prebuilt images (IMAGE_PREFIX, IMAGE_TAG)
#   infra/deploy/scripts/deploy.sh --build         build here even if IMAGE_PREFIX is a registry
#   ... --pull-images --fresh                      pull even if the images of a commit are here (rebuilt)
#
# Images: IMAGE_PREFIX/IMAGE_TAG from the environment (the GitHub workflow), else from .env, else
# the ones the last deploy recorded in infra/deploy/.env.images, else localhost/rozklad:prod (built
# here). A registry prefix (ghcr.io/<user>/rozklad) means pulling. The images it starts are recorded
# in .env.images, so compose.sh and restore-db.sh keep using them. After the stack is healthy (auth is
# ready only once an admin can sign in) it reloads Caddy and runs smoke checks. docs/DEPLOY.md
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

usage() { sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

original_args=("$@")
git_pull=1
fresh=0
mode=auto
while (($#)); do
  case $1 in
    --no-pull) git_pull=0 ;;
    --pull-images) mode=pull ;;
    --build) mode=build ;;
    --fresh) fresh=1 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done

app_services=(auth core scheduler importer notification gateway web)

version_ge() { [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" == "$2" ]]; }

preflight() {
  command -v docker >/dev/null || die "Docker is not installed (docs/DEPLOY.md, step 3)"
  docker info >/dev/null 2>&1 ||
    die "cannot reach the Docker daemon: add your user to the 'docker' group and log in again"
  local compose_version
  compose_version=$(docker compose version --short 2>/dev/null) ||
    die "the Docker Compose plugin is missing: sudo apt-get install docker-compose-plugin"
  compose_version=${compose_version#v}
  version_ge "${compose_version%%-*}" 2.24.4 ||
    die "Docker Compose $compose_version is too old: 2.24.4 or newer is needed (!reset tag)"

  [[ -f $ROZKLAD_ENV_FILE ]] ||
    die "$ROZKLAD_ENV_FILE is missing: run infra/deploy/scripts/gen-secrets.sh, then fill it in"
  if grep -q $'\r' "$ROZKLAD_ENV_FILE"; then
    die "$ROZKLAD_ENV_FILE has Windows line endings; fix with: sed -i 's/\\r\$//' $ROZKLAD_ENV_FILE"
  fi
  local placeholders
  placeholders=$(grep -E '^[A-Za-z_][A-Za-z0-9_]*=change-me$' "$ROZKLAD_ENV_FILE" | cut -d= -f1 | tr '\n' ' ' || true)
  [[ -z $placeholders ]] || die "still 'change-me' in .env: ${placeholders}- run infra/deploy/scripts/gen-secrets.sh"

  local domain email key
  domain=$(env_get DOMAIN)
  [[ $domain =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ && $domain == *.* ]] ||
    die "DOMAIN must be a host name such as rozklad.example.org (no https://, no path), got '$domain'"
  [[ $domain != example.com && $domain != *.example.com ]] || die "set DOMAIN in $ROZKLAD_ENV_FILE"
  email=$(env_get ACME_EMAIL)
  [[ $email == ?*@?*.?* ]] || die "ACME_EMAIL must be an e-mail address, got '$email'"
  [[ $email != *@example.com ]] || die "Let's Encrypt refuses example.com addresses: set ACME_EMAIL"

  # the token signing key of auth (a bind-mounted file: a missing one would become an empty directory)
  key="$ROZKLAD_DEPLOY_DIR/secrets/auth-signing-key.pem"
  [[ -f $key ]] || die "$key is missing: run infra/deploy/scripts/gen-secrets.sh"
  grep -q -- '-----BEGIN PRIVATE KEY-----' "$key" ||
    die "$key is not a PKCS#8 PEM key; move it away and run infra/deploy/scripts/gen-secrets.sh"
  if [[ $(env_get AUTH_BOOTSTRAP_FORCE false) == true ]]; then
    warn "AUTH_BOOTSTRAP_FORCE=true resets the password of $(env_get AUTH_BOOTSTRAP_ADMIN_USERNAME admin) at every start:
     set it back to false after you have signed in"
  elif [[ -n $(env_get AUTH_BOOTSTRAP_ADMIN_PASSWORD) ]]; then
    # auth ignores it while an admin can sign in, but it stays readable in .env and in the container
    warn "AUTH_BOOTSTRAP_ADMIN_PASSWORD is still set: once you have signed in as $(env_get AUTH_BOOTSTRAP_ADMIN_USERNAME admin),
     empty it in .env (AUTH_BOOTSTRAP_ADMIN_PASSWORD=) and run deploy.sh again"
  fi

  compose config --quiet || die "the compose configuration is invalid (see above)"

  local mem_kb swap_kb
  mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
  swap_kb=$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
  if ((mem_kb > 0 && mem_kb < 7 * 1024 * 1024 && swap_kb < 1024 * 1024)); then
    warn "$((mem_kb / 1024)) MB of RAM and no swap: add a 2 GB swap file (docs/DEPLOY.md, step 3)"
  fi
  ok "preflight: $(uname -m), Docker Compose $compose_version, $ROZKLAD_ENV_FILE"
}

# image_setting KEY DEFAULT: environment, then .env, then (unless --build) the record of the last
# deploy, then DEFAULT. --build ignores the record: it builds local images under the local name.
image_setting() {
  local key=$1 default=$2 value
  value=$(env_get "$key")
  if [[ -z $value && $mode != build ]]; then
    value=$(env_file_value "$ROZKLAD_IMAGES_FILE" "$key")
  fi
  printf '%s' "${value:-$default}"
}

app_images_present() {
  local service
  for service in "${app_services[@]}"; do
    docker image inspect "$prefix/$service:$tag" >/dev/null 2>&1 || return 1
  done
}

# what runs from now on; compose.sh, restore-db.sh and backup-db.sh read it through lib.sh
record_images() {
  local tmp="$ROZKLAD_IMAGES_FILE.tmp"
  {
    printf '# Written by deploy.sh (%s): the images that run now. Do not edit; to change them set\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '# IMAGE_PREFIX/IMAGE_TAG in .env and run deploy.sh.\n'
    printf 'IMAGE_PREFIX=%s\nIMAGE_TAG=%s\n' "$prefix" "$tag"
  } >"$tmp"
  mv "$tmp" "$ROZKLAD_IMAGES_FILE"
}

build_jars() {
  # Maven runs in a container, so the VPS needs neither a JDK nor Maven; the jars are platform
  # independent, the images are then built for the architecture of this machine.
  local m2=${MAVEN_CACHE_DIR:-$HOME/.m2}
  mkdir -p "$m2"
  log "Building the jars (Maven in a container; the first run downloads the dependencies)"
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e MAVEN_CONFIG=/var/maven/.m2 \
    -e MAVEN_OPTS="-Xmx1g -XX:+UseSerialGC" \
    -v "$m2:/var/maven/.m2" \
    -v "$ROZKLAD_REPO_DIR:/workspace" -w /workspace \
    "${MAVEN_IMAGE:-maven:3.9.11-eclipse-temurin-25}" \
    mvn -B -q -ntp -Duser.home=/var/maven -DskipTests package
}

# The Caddyfile directory is bind-mounted, so the container already sees the pulled file, but
# Caddy reads it only on start or reload, and compose does not recreate Caddy for a changed file.
reload_caddy() {
  local out
  if out=$(compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1); then
    ok "Caddy        configuration reloaded from infra/deploy/caddy/Caddyfile"
  else
    printf '%s\n' "$out" >&2
    warn "caddy reload failed, Caddy keeps the previous configuration. Check the file with:
     infra/deploy/scripts/compose.sh exec caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile"
  fi
}

# https request to the local Caddy with the right SNI, whatever DNS says
probe() { curl -sS --max-time 20 --resolve "$1:443:127.0.0.1" "${@:3}" "https://$1$2"; }

smoke_checks() {
  local domain status headers discovery location rate burst count limited
  domain=$(env_get DOMAIN)
  log "Smoke checks through Caddy"

  status=$(probe "$domain" / -k -o /dev/null -w '%{http_code}' 2>/dev/null || true)
  if [[ $status == 200 ]]; then
    ok "SPA          https://$domain/ -> 200"
  else
    warn "SPA https://$domain/ -> ${status:-no answer}; see: compose.sh logs caddy web"
  fi

  # the gateway puts X-Request-Id on every response: its presence proves Caddy -> gateway works
  headers=$(probe "$domain" /api/public/deploy-check -k -D - -o /dev/null 2>/dev/null || true)
  status=$(awk 'NR == 1 {print $2}' <<<"$headers")
  if grep -qi '^x-request-id:' <<<"$headers" && [[ ${status:-500} -lt 500 ]]; then
    ok "API          https://$domain/api/... reaches the gateway (HTTP $status)"

    # Forged forwarding headers must not give a client a new bucket of the per-IP limit for every
    # request. Many requests, each with its own forged address: some of them must get 429. Only the
    # bucket of this server's own address is used up, for a second or two.
    rate=$(env_get PUBLIC_RATE_LIMIT_PER_SECOND 20)
    burst=$(env_get PUBLIC_RATE_LIMIT_BURST 40)
    if [[ $rate =~ ^[0-9]+$ && $burst =~ ^[0-9]+$ ]]; then
      count=$(((rate + burst) * 3))
      ((count <= 900)) || count=900
      limited=$(seq 1 "$count" | xargs -P 32 -I{} curl -sS -k --max-time 20 \
        --resolve "$domain:443:127.0.0.1" -o /dev/null -w '%{http_code}\n' \
        -H 'Forwarded: for="[2001:db8::{}]"' -H 'X-Forwarded-For: 2001:db8::{}' \
        "https://$domain/api/public/deploy-check" 2>/dev/null | grep -c '^429$' || true)
      if ((${limited:-0} > 0)); then
        ok "Rate limit   per client: $limited of $count requests with forged Forwarded headers got 429"
      else
        warn "Rate limit   none of $count requests with forged Forwarded/X-Forwarded-For headers got 429:
     either the per-IP limit of /api/public/** can be bypassed (check strip_client_forwarding in the
     Caddyfile) or this server sent the requests too slowly."
      fi
    fi
  else
    warn "API          https://$domain/api/... -> ${status:-no answer}; see: compose.sh logs gateway core"
  fi

  # the sign-in service: fixed issuer, sign-in redirect on this origin, internal API closed
  discovery=$(probe "$domain" /auth/.well-known/openid-configuration -k 2>/dev/null || true)
  if [[ $discovery == *"\"issuer\":\"https://$domain/auth\""* ]]; then
    ok "Sign-in      issuer https://$domain/auth"
  else
    warn "discovery at https://$domain/auth does not show the issuer https://$domain/auth; see: compose.sh logs auth caddy"
  fi
  location=$(probe "$domain" "/auth/oauth2/authorize?response_type=code&client_id=rozklad-web&scope=openid&state=smoke&redirect_uri=https%3A%2F%2F$domain%2Flogin%2Fcallback&code_challenge=47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU&code_challenge_method=S256" \
    -k -H 'Accept: text/html' -o /dev/null -w '%{redirect_url}' 2>/dev/null || true)
  if [[ $location == "https://$domain/auth/login" ]]; then
    ok "Sign-in      an authorize request without a session goes to https://$domain/auth/login"
  else
    warn "an authorize request without a session redirects to '${location:-nothing}', expected
     https://$domain/auth/login (forwarded headers between Caddy and auth); see: compose.sh logs auth"
  fi
  status=$(probe "$domain" /auth/internal/accounts -k -o /dev/null -w '%{http_code}' 2>/dev/null || true)
  if [[ $status == 404 ]]; then
    ok "Sign-in      /auth/internal is closed (404)"
  else
    warn "https://$domain/auth/internal answered ${status:-nothing}, expected 404 (Caddyfile, auth_routes)"
  fi
  status=$(probe "$domain" /api/internal/catalog/snapshot -k -o /dev/null -w '%{http_code}' 2>/dev/null || true)
  if [[ $status == 404 ]]; then
    ok "API          /api/internal is closed (404)"
  else
    warn "https://$domain/api/internal answered ${status:-nothing}, expected 404 (Caddyfile, api_routes)"
  fi

  if probe "$domain" / -o /dev/null 2>/dev/null; then
    ok "TLS          trusted certificate for $domain"
  else
    warn "the TLS certificate is not trusted (yet). DNS of $domain must point to this server and ports
     80/443 must be open in the cloud firewall; Caddy retries by itself. See: compose.sh logs caddy"
  fi
  if [[ $(env_get ACME_CA) == *staging* ]]; then
    warn "ACME_CA is the Let's Encrypt staging CA: browsers will not trust the certificates"
  fi
}

# Pulled images keep their commit tags, so `docker image prune` never removes them. Keep the
# current and the previous deploy (for a quick rollback) and remove the other tags of this prefix.
prune_images() {
  local keep_previous='' old
  docker image prune -f >/dev/null 2>&1 || true
  [[ $mode == pull ]] || return 0
  if [[ $previous_prefix == "$prefix" ]]; then
    keep_previous=$previous_tag
  fi
  old=$(docker image ls --format '{{.Repository}}:{{.Tag}}' |
    awk -v p="$prefix/" -v t="$tag" -v k="$keep_previous" '
      index($0, p) == 1 { n = split($0, part, ":"); if (part[n] != t && part[n] != k && part[n] != "<none>") print }')
  if [[ -n $old ]]; then
    log "Removing the images of older deploys (keeping :$tag${keep_previous:+ and :$keep_previous})"
    xargs -r docker image rm <<<"$old" >/dev/null 2>&1 || true
  fi
}

summary() {
  local domain
  domain=$(env_get DOMAIN)
  printf '\n'
  log "Done"
  printf '  App:       https://%s\n' "$domain"
  printf '  Sign-in:   https://%s/auth/login\n' "$domain"
  printf '  Images:    %s/<service>:%s (recorded in %s)\n' "$prefix" "$tag" "$ROZKLAD_IMAGES_FILE"
  printf '  Admin:     %s (first password: AUTH_BOOTSTRAP_ADMIN_PASSWORD in %s)\n' \
    "$(env_get AUTH_BOOTSTRAP_ADMIN_USERNAME admin)" "$ROZKLAD_ENV_FILE"
  if [[ -n $(env_get DEMO_TEACHER_PASSWORD) ]]; then
    printf '  Demo:      krentsin, nepyivoda (password: DEMO_TEACHER_PASSWORD)\n'
  fi
  printf '  Logs:      infra/deploy/scripts/compose.sh logs -f --tail=100 <service>\n'
  if [[ -n $(env_get TELEGRAM_BOT_TOKEN) ]]; then
    printf '  Telegram:  the bot now polls from this server; stop every other instance using the same token\n'
  fi
}

# ---------------------------------------------------------------------------------------------------

require_server
cd "$ROZKLAD_REPO_DIR"

if ((git_pull)) && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  log "Updating the repository (git pull --ff-only)"
  git pull --ff-only
  # continue with the scripts and compose files that were just pulled
  exec bash "$here/deploy.sh" --no-pull "${original_args[@]}"
fi

preflight

prefix=$(image_setting IMAGE_PREFIX "$ROZKLAD_DEFAULT_IMAGE_PREFIX")
tag=$(image_setting IMAGE_TAG "$ROZKLAD_DEFAULT_IMAGE_TAG")
# the values end up in .env.images: keep them to what an image reference allows
[[ $prefix =~ ^[a-z0-9]([a-z0-9._/:-]*[a-z0-9])?$ ]] || die "IMAGE_PREFIX is not a valid image name prefix: '$prefix'"
[[ $tag =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "IMAGE_TAG is not a valid image tag: '$tag'"
if [[ $mode == auto ]]; then
  # a registry prefix means pulling; local names (localhost/rozklad) are built here
  if [[ $prefix == */* && $prefix != localhost/* ]]; then mode=pull; else mode=build; fi
fi
# the process environment wins over .env and .env.images in every compose call below
export IMAGE_PREFIX=$prefix IMAGE_TAG=$tag
previous_prefix=$(env_file_value "$ROZKLAD_IMAGES_FILE" IMAGE_PREFIX)
previous_tag=$(env_file_value "$ROZKLAD_IMAGES_FILE" IMAGE_TAG)

if [[ $mode == build ]]; then
  build_jars
  log "Building the images for $(uname -m) as $prefix/<service>:$tag"
  compose build --pull
  # patch releases of the pinned third-party images (Caddy, PostgreSQL, RabbitMQ, Redis)
  compose pull --ignore-buildable --quiet
elif ((!fresh)) && [[ $tag =~ ^[0-9a-f]{40}$ ]] && app_images_present; then
  # a commit tag never changes: no registry login needed to deploy it again
  log "$prefix/<service>:$tag is already here; pulling only the third-party images"
  compose pull --ignore-buildable --quiet
else
  log "Pulling $prefix/<service>:$tag"
  compose pull
fi
record_images

log "Starting the stack (auth is ready once an admin can sign in: AUTH_BOOTSTRAP_ADMIN_* on the first start)"
if ! compose up -d --no-build --remove-orphans --wait --wait-timeout "${DEPLOY_WAIT_TIMEOUT:-900}"; then
  compose ps
  die "not every service became healthy; see: infra/deploy/scripts/compose.sh logs --tail=200 <service>"
fi

reload_caddy

smoke_checks
compose ps --format 'table {{.Service}}\t{{.Status}}'
prune_images
summary
