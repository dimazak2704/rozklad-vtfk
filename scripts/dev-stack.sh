#!/usr/bin/env bash
# Local stack of «Розклад ВТФК» in Docker: infrastructure, the Java services, the sign-in service auth and
# the web container (nginx: SPA, /api, /auth) (docs/RUNNING.md).
#
#   scripts/dev-stack.sh up [--no-telegram] [--no-build] [--no-web] [--google-mock]
#                                  build jars and images, start everything detached, wait until healthy;
#                                  web starts by default (sign-in goes through it), --web is accepted as a no-op;
#                                  --google-mock adds «Увійти через Google» against a local mock-oauth2-server
#                                  (infra/docker-compose.google-mock.yml, http://localhost:8090/google);
#                                  GOOGLE_MOCK_HOSTED_DOMAIN=vtc.vn.ua turns the domain rule on
#   scripts/dev-stack.sh down      stop the app services (infrastructure and data stay)
#   scripts/dev-stack.sh down --all   stop everything incl. postgres/rabbitmq/redis/keycloak (data kept in volumes)
#   scripts/dev-stack.sh status    containers, health, memory, URLs
#   scripts/dev-stack.sh seed [step ...]   real data through the API (scripts/seed_demo.py)
#   scripts/dev-stack.sh reset-data --yes  empty the core/importer/scheduler/notification databases
#                                  (auth, Keycloak and its realm stay); then `up` and `seed` again
#   scripts/dev-stack.sh logs <service>    follow the logs of one service
#
# Telegram: by default notification polls the bot of TELEGRAM_BOT_TOKEN from .env. One token can be
# polled by one process only, so use --no-telegram (or TELEGRAM=off) when another copy runs the bot.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

services=(auth core scheduler importer notification gateway)
compose_files=(-f infra/docker-compose.yml)
env_args=()
[[ -f .env ]] && env_args=(--env-file .env)

compose() {
  docker compose "${env_args[@]}" "${compose_files[@]}" --profile app "$@"
}

python_cmd() {
  if command -v python >/dev/null 2>&1; then echo python; else echo python3; fi
}

wait_healthy() {
  local deadline=$((SECONDS + 300)) name state
  for name in "$@"; do
    while :; do
      state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "rozklad-$name-1" 2>/dev/null || echo missing)
      [[ $state == healthy ]] && { echo "  $name: healthy"; break; }
      if ((SECONDS > deadline)); then
        echo "  $name: $state after 5 min — see: scripts/dev-stack.sh logs $name" >&2
        return 1
      fi
      sleep 3
    done
  done
}

# Realms imported before the fix lack the scope kc-admin-api on rozklad-core-admin; without it Keycloak
# 26.8 answers 401 to core's admin calls (invites, linking accounts). Idempotent.
keycloak_admin_scope() {
  MSYS_NO_PATHCONV=1 docker exec rozklad-keycloak-1 bash -c '
    set -euo pipefail
    cfg=$(mktemp); trap "rm -f $cfg" EXIT
    kc() { /opt/keycloak/bin/kcadm.sh "$@" --config "$cfg"; }
    kc config credentials --server http://localhost:8080 --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
    scope=$(kc get client-scopes -r rozklad --fields id,name --format csv --noquotes | sed -n "s/^\([^,]*\),kc-admin-api$/\1/p")
    client=$(kc get clients -r rozklad -q clientId=rozklad-core-admin --fields id --format csv --noquotes)
    kc update "clients/$client/default-client-scopes/$scope" -r rozklad'
  echo "  keycloak: rozklad-core-admin has the scope kc-admin-api"
}

# The user profile of infra/keycloak/user-profile.json: e-mail and first name are optional, so a teacher
# invited without them (most teachers of the Excel have initials only) signs in right after accepting
# the invite instead of stopping at «Update account information». Realms imported before 08.11.2026 have
# the Keycloak default (both required). Idempotent.
keycloak_user_profile() {
  MSYS_NO_PATHCONV=1 docker exec -i rozklad-keycloak-1 bash -c '
    set -euo pipefail
    cfg=$(mktemp); trap "rm -f $cfg" EXIT
    kc() { /opt/keycloak/bin/kcadm.sh "$@" --config "$cfg"; }
    kc config credentials --server http://localhost:8080 --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
    kc update users/profile -r rozklad -f - >/dev/null' < "$root/infra/keycloak/user-profile.json"
  echo "  keycloak: user profile without required e-mail and first name"
}

# Databases added after the postgres volume was created: init.sql runs only on an empty volume. Idempotent.
ensure_databases() {
  docker exec -i rozklad-postgres-1 psql -q -v ON_ERROR_STOP=1 -U postgres -d postgres >/dev/null <<'SQL'
SELECT 'CREATE USER auth WITH PASSWORD ''auth''' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auth')\gexec
SELECT 'CREATE DATABASE auth OWNER auth ENCODING ''UTF8''' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'auth')\gexec
REVOKE CONNECT ON DATABASE auth FROM PUBLIC;
SQL
  echo "  postgres: database auth is present"
}

# The sign-in service through nginx, the way a browser reaches it: discovery must name the fixed issuer and an
# authorize request without a session must land on /auth/login of the same origin (forwarded host and port).
auth_smoke() {
  local base=http://localhost:8088 issuer location
  issuer=$(curl -fsS --resolve localhost:8088:127.0.0.1 "$base/auth/.well-known/openid-configuration" |
    sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p')
  if [[ $issuer != "$base/auth" ]]; then
    echo "  auth smoke: discovery issuer is '$issuer', expected $base/auth" >&2
    return 1
  fi
  location=$(curl -sS -o /dev/null -w '%{redirect_url}' --resolve localhost:8088:127.0.0.1 -H 'Accept: text/html' \
    "$base/auth/oauth2/authorize?response_type=code&client_id=rozklad-web&scope=openid&state=smoke&redirect_uri=http%3A%2F%2Flocalhost%3A8088%2Flogin%2Fcallback&code_challenge=47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU&code_challenge_method=S256")
  if [[ $location != "$base/auth/login" ]]; then
    echo "  auth smoke: authorize without a session redirects to '$location', expected $base/auth/login" >&2
    return 1
  fi
  echo "  auth: discovery and sign-in redirect through nginx are correct"
}

# The mock of an earlier `up --google-mock`: stopped when the stack comes up without it.
stop_google_mock() {
  if docker ps -q --filter name=rozklad-mock-oidc-1 | grep -q .; then
    docker stop rozklad-mock-oidc-1 >/dev/null
    echo "  mock-oidc: stopped (started without --google-mock)"
  fi
}

cmd_up() {
  local build=1 web=1 telegram=${TELEGRAM:-on} google_mock=0
  for arg in "$@"; do
    case $arg in
      --google-mock) google_mock=1 ;;
      --no-telegram) telegram=off ;;
      --no-build) build=0 ;;
      --web) web=1 ;;
      --no-web) web=0 ;;
      *) echo "unknown option $arg" >&2; exit 2 ;;
    esac
  done
  [[ $telegram == off ]] && compose_files+=(-f infra/docker-compose.no-telegram.yml)
  ((google_mock)) && compose_files+=(-f infra/docker-compose.google-mock.yml)
  local targets=("${services[@]}")
  ((web)) && targets+=(web)
  ((google_mock)) || stop_google_mock

  echo "== infrastructure"
  docker compose "${env_args[@]}" "${compose_files[@]}" --profile infra up -d
  wait_healthy postgres rabbitmq redis keycloak
  ensure_databases
  keycloak_admin_scope
  keycloak_user_profile

  if ((build)); then
    echo "== jars (mvn package, tests skipped)"
    # shellcheck source=env.sh
    source "$root/scripts/env.sh"
    mvn -q -DskipTests package -pl "$(printf 'services/%s,' "${services[@]}" | sed 's/,$//')" -am
    echo "== images"
    for service in "${targets[@]}"; do
      compose build "$service"
    done
  fi
  echo "== services (telegram: $telegram, google mock: $( ((google_mock)) && echo on || echo off))"
  ((google_mock)) && compose up -d mock-oidc
  compose up -d --no-build "${targets[@]}"
  wait_healthy "${services[@]}"
  if ((web)); then
    wait_healthy web
    auth_smoke
  fi
  cmd_status
  if ((google_mock)); then
    echo "Mock Google     http://localhost:8090/google   (any name, claims e.g. {\"email\":\"krentsin@rozklad.local\"})"
  fi
}

cmd_down() {
  if [[ ${1:-} == --all ]]; then
    docker compose "${env_args[@]}" "${compose_files[@]}" --profile app --profile infra stop
  else
    compose stop "${services[@]}" web
  fi
  stop_google_mock
}

cmd_status() {
  docker ps --filter name=rozklad- --format 'table {{.Names}}\t{{.Status}}'
  echo
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}' $(docker ps -q --filter name=rozklad-) 2>/dev/null || true
  cat <<'EOF'

API (gateway)   http://localhost:8080   e.g. /api/public/meta, /api/public/schedule/group/{id}
core            http://localhost:8081/swagger-ui.html      scheduler :8082  importer :8083  notification :8084
Site            http://localhost:8088
Sign-in         http://localhost:8088/auth/login   (service auth; host-run services: http://localhost:8085/auth)
Token (scripts) python scripts/rozklad_auth.py token --user admin
Keycloak        http://localhost:8180   (no longer used for sign-in; removed soon)
RabbitMQ UI     http://localhost:15672  (rozklad / rozklad)    PostgreSQL localhost:5433 (postgres / postgres)
Demo users      infra/dev/demo-users.env (admin = ADMIN, krentsin / nepyivoda = TEACHER)
EOF
}

cmd_reset_data() {
  [[ ${1:-} == --yes ]] || { echo "reset-data deletes all data of core, importer, scheduler and notification; add --yes" >&2; exit 2; }
  compose stop "${services[@]}"
  local db
  for db in core importer scheduler notification; do
    docker exec rozklad-postgres-1 psql -q -U postgres -d "$db" -c \
      "DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION $db;"
    echo "  $db: emptied"
  done
  # події старого стану в чергах більше нікому не потрібні
  docker exec rozklad-rabbitmq-1 sh -c 'for q in $(rabbitmqctl -q list_queues --no-table-headers name); do rabbitmqctl -q purge_queue "$q"; done'

  docker exec rozklad-redis-1 redis-cli FLUSHALL >/dev/null
  echo "Done. Next: scripts/dev-stack.sh up --no-build && scripts/dev-stack.sh seed"
}

case ${1:-} in
  up) shift; cmd_up "$@" ;;
  down) shift; cmd_down "$@" ;;
  status) cmd_status ;;
  seed) shift; PYTHONIOENCODING=utf-8 "$(python_cmd)" "$root/scripts/seed_demo.py" "$@" ;;
  reset-data) shift; cmd_reset_data "$@" ;;
  logs) shift; compose logs -f --tail=200 "${1:?service}" ;;
  *) sed -n '2,18p' "$0"; exit 2 ;;
esac
