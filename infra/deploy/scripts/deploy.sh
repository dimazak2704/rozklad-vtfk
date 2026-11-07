#!/usr/bin/env bash
# Deploys or updates «Розклад ВТФК» on the VPS. One command for the first start and for updates.
#
#   infra/deploy/scripts/deploy.sh                 git pull, build the images here, start, check
#   infra/deploy/scripts/deploy.sh --no-pull       deploy the working tree as it is
#   infra/deploy/scripts/deploy.sh --pull-images   use prebuilt images (IMAGE_PREFIX, IMAGE_TAG)
#   infra/deploy/scripts/deploy.sh --build         build here even if IMAGE_PREFIX is a registry
#
# IMAGE_PREFIX with a registry (ghcr.io/<user>/rozklad) switches to pulling automatically; the
# GitHub workflow passes IMAGE_PREFIX/IMAGE_TAG in the environment, which wins over .env.
# After the stack is healthy it syncs Keycloak with .env, creates the demo accounts and runs smoke
# checks through Caddy. Guide: docs/DEPLOY.md.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

usage() { sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

original_args=("$@")
git_pull=1
mode=auto
while (($#)); do
  case $1 in
    --no-pull) git_pull=0 ;;
    --pull-images) mode=pull ;;
    --build) mode=build ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
  shift
done

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
    die "Docker Compose $compose_version is too old: 2.24.4 or newer is needed (!reset/!override tags)"

  [[ -f $ROZKLAD_ENV_FILE ]] ||
    die "$ROZKLAD_ENV_FILE is missing: run infra/deploy/scripts/gen-secrets.sh, then fill it in"
  if grep -q $'\r' "$ROZKLAD_ENV_FILE"; then
    die "$ROZKLAD_ENV_FILE has Windows line endings; fix with: sed -i 's/\\r\$//' $ROZKLAD_ENV_FILE"
  fi
  local placeholders
  placeholders=$(grep -E '^[A-Za-z_][A-Za-z0-9_]*=change-me$' "$ROZKLAD_ENV_FILE" | cut -d= -f1 | tr '\n' ' ' || true)
  [[ -z $placeholders ]] || die "still 'change-me' in .env: ${placeholders}- run infra/deploy/scripts/gen-secrets.sh"

  local domain email extra flag
  domain=$(env_get DOMAIN)
  [[ $domain =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ && $domain == *.* ]] ||
    die "DOMAIN must be a host name such as rozklad.example.org (no https://, no path), got '$domain'"
  [[ $domain != example.com && $domain != *.example.com ]] || die "set DOMAIN in $ROZKLAD_ENV_FILE"
  email=$(env_get ACME_EMAIL)
  [[ $email == ?*@?*.?* ]] || die "ACME_EMAIL must be an e-mail address, got '$email'"
  [[ $email != *@example.com ]] || die "Let's Encrypt refuses example.com addresses: set ACME_EMAIL"
  extra=$(env_get EXTRA_WEB_ORIGIN)
  # exact origin: CORS compares it character by character with the browser's Origin header
  [[ -z $extra ]] || is_origin "$extra" ||
    die "EXTRA_WEB_ORIGIN must look like https://name.netlify.app (no path, no trailing slash), got '$extra'"
  for flag in KC_ADMIN_ALLOWED API_SITE_ENABLED; do
    [[ $(env_get "$flag" 0) =~ ^[01]$ ]] || die "$flag must be 0 or 1"
  done

  compose config --quiet || die "the compose configuration is invalid (see above)"

  local mem_kb swap_kb
  mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
  swap_kb=$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
  if ((mem_kb > 0 && mem_kb < 7 * 1024 * 1024 && swap_kb < 1024 * 1024)); then
    warn "$((mem_kb / 1024)) MB of RAM and no swap: add a 2 GB swap file (docs/DEPLOY.md, step 3)"
  fi
  ok "preflight: $(uname -m), Docker Compose $compose_version, $ROZKLAD_ENV_FILE"
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

# https request to the local Caddy with the right SNI, whatever DNS says
probe() { curl -sS --max-time 20 --resolve "$1:443:127.0.0.1" "${@:3}" "https://$1$2"; }

smoke_checks() {
  local domain auth status headers discovery csp
  domain=$(env_get DOMAIN)
  auth="auth.$domain"
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
  else
    warn "API          https://$domain/api/... -> ${status:-no answer}; see: compose.sh logs gateway core"
  fi

  discovery=$(probe "$auth" /realms/rozklad/.well-known/openid-configuration -k 2>/dev/null || true)
  if [[ $discovery == *"\"issuer\":\"https://$auth/realms/rozklad\""* ]]; then
    ok "Keycloak     issuer https://$auth/realms/rozklad"
  else
    warn "Keycloak discovery at https://$auth/realms/rozklad does not show the expected issuer; see: compose.sh logs keycloak"
  fi

  status=$(probe "$auth" /admin/ -k -o /dev/null -w '%{http_code}' 2>/dev/null || true)
  if [[ $(env_get KC_ADMIN_ALLOWED 0) == 0 ]]; then
    if [[ $status == 404 ]]; then
      ok "Keycloak     /admin is closed (404)"
    else
      warn "Keycloak /admin answered ${status:-nothing}, expected 404"
    fi
  fi

  csp=$(probe "$domain" / -k -I 2>/dev/null | grep -i '^content-security-policy:' || true)
  if [[ -n $csp && $csp != *"https://$auth"* ]]; then
    warn "the SPA's Content-Security-Policy does not allow https://$auth, so the login will be blocked.
     The Caddyfile rewrites http://localhost:8180 from web/nginx.conf: update the rewrite if nginx.conf changed."
  fi

  if probe "$domain" / -o /dev/null 2>/dev/null && probe "$auth" /realms/rozklad -o /dev/null 2>/dev/null; then
    ok "TLS          trusted certificates for $domain and $auth"
  else
    warn "TLS certificates are not trusted (yet). DNS of $domain and $auth must point to this server and
     ports 80/443 must be open in the cloud firewall; Caddy retries by itself. See: compose.sh logs caddy"
  fi
  if [[ $(env_get ACME_CA) == *staging* ]]; then
    warn "ACME_CA is the Let's Encrypt staging CA: browsers will not trust the certificates"
  fi
}

summary() {
  local domain
  domain=$(env_get DOMAIN)
  printf '\n'
  log "Done"
  printf '  App:       https://%s\n' "$domain"
  printf '  Login:     https://auth.%s (realm rozklad)\n' "$domain"
  if [[ $(env_get API_SITE_ENABLED 0) == 1 ]]; then
    printf '  API host:  https://api.%s/api\n' "$domain"
  fi
  printf '  Accounts:  admin, krentsin, nepyivoda (passwords: DEMO_*_PASSWORD in %s)\n' "$ROZKLAD_ENV_FILE"
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

prefix=$(env_get IMAGE_PREFIX rozklad)
tag=$(env_get IMAGE_TAG prod)
if [[ $mode == auto ]]; then
  if [[ $prefix == */* ]]; then mode=pull; else mode=build; fi
fi

if [[ $mode == build ]]; then
  build_jars
  log "Building the images for $(uname -m) as $prefix/<service>:$tag"
  compose build --pull
  # patch releases of the pinned third-party images (Caddy, PostgreSQL, RabbitMQ, Redis, Keycloak)
  compose pull --ignore-buildable --quiet
else
  log "Pulling $prefix/<service>:$tag"
  compose pull
fi

log "Starting the stack (the first start takes a few minutes: Keycloak builds itself and imports the realm)"
if ! compose up -d --no-build --remove-orphans --wait --wait-timeout "${DEPLOY_WAIT_TIMEOUT:-900}"; then
  compose ps
  die "not every service became healthy; see: infra/deploy/scripts/compose.sh logs --tail=200 <service>"
fi

bash "$here/keycloak-sync.sh"
if [[ -n "$(env_get DEMO_ADMIN_PASSWORD)$(env_get DEMO_TEACHER_PASSWORD)" ]]; then
  bash "$here/create-demo-users.sh"
fi

smoke_checks
compose ps --format 'table {{.Service}}\t{{.Status}}'
docker image prune -f >/dev/null 2>&1 || true
summary
