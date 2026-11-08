#!/usr/bin/env bash
# Local stack of «Розклад ВТФК» in Docker: infrastructure + the five Java services (docs/RUNNING.md).
#
#   scripts/dev-stack.sh up [--no-telegram] [--no-build] [--web]
#                                  build jars and images, start everything detached, wait until healthy
#   scripts/dev-stack.sh down      stop the app services (infrastructure and data stay)
#   scripts/dev-stack.sh down --all   stop everything incl. postgres/rabbitmq/redis/keycloak (data kept in volumes)
#   scripts/dev-stack.sh status    containers, health, memory, URLs
#   scripts/dev-stack.sh seed [step ...]   real data through the API (scripts/seed_demo.py)
#   scripts/dev-stack.sh reset-data --yes  empty the core/importer/scheduler/notification databases
#                                  (Keycloak and its realm stay); then `up` and `seed` again
#   scripts/dev-stack.sh logs <service>    follow the logs of one service
#
# Telegram: by default notification polls the bot of TELEGRAM_BOT_TOKEN from .env. One token can be
# polled by one process only, so use --no-telegram (or TELEGRAM=off) when another copy runs the bot.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

services=(core scheduler importer notification gateway)
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

cmd_up() {
  local build=1 web=0 telegram=${TELEGRAM:-on}
  for arg in "$@"; do
    case $arg in
      --no-telegram) telegram=off ;;
      --no-build) build=0 ;;
      --web) web=1 ;;
      *) echo "unknown option $arg" >&2; exit 2 ;;
    esac
  done
  [[ $telegram == off ]] && compose_files+=(-f infra/docker-compose.no-telegram.yml)
  local targets=("${services[@]}")
  ((web)) && targets+=(web)

  echo "== infrastructure"
  docker compose "${env_args[@]}" "${compose_files[@]}" --profile infra up -d
  wait_healthy postgres rabbitmq redis keycloak
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
  echo "== services (telegram: $telegram)"
  compose up -d --no-build "${targets[@]}"
  wait_healthy "${services[@]}"
  cmd_status
}

cmd_down() {
  if [[ ${1:-} == --all ]]; then
    docker compose "${env_args[@]}" "${compose_files[@]}" --profile app --profile infra stop
  else
    compose stop "${services[@]}" web
  fi
}

cmd_status() {
  docker ps --filter name=rozklad- --format 'table {{.Names}}\t{{.Status}}'
  echo
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}' $(docker ps -q --filter name=rozklad-) 2>/dev/null || true
  cat <<'EOF'

API (gateway)   http://localhost:8080   e.g. /api/public/meta, /api/public/schedule/group/{id}
core            http://localhost:8081/swagger-ui.html      scheduler :8082  importer :8083  notification :8084
Keycloak        http://localhost:8180   (admin console: admin / admin, realm rozklad)
RabbitMQ UI     http://localhost:15672  (rozklad / rozklad)    PostgreSQL localhost:5433 (postgres / postgres)
Demo users      infra/keycloak/demo-users.env (admin = ADMIN, krentsin / nepyivoda = TEACHER)
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
  *) sed -n '2,16p' "$0"; exit 2 ;;
esac
