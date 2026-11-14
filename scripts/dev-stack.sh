#!/usr/bin/env bash
# Local stack of «Розклад ВТФК» in Docker: infrastructure, the Java services, the sign-in service auth and
# the web container (nginx: SPA, /api, /auth) (docs/RUNNING.md).
#
#   scripts/dev-stack.sh up [--no-telegram] [--no-build] [--no-web] [--google-mock] [--time 2026-11-20T10:00]
#                           [--lan[=192.168.1.20]]
#                                  build jars and images, start everything detached, wait until healthy;
#                                  --time starts core, importer and notification at that Kyiv date-time ("stand
#                                  time", development only) and lets it tick on; without it they run on real time
#                                  web starts by default (sign-in goes through it), --web is accepted as a no-op;
#                                  --google-mock adds «Увійти через Google» against a local mock-oauth2-server
#                                  (infra/docker-compose.google-mock.yml, http://localhost:8090/google);
#                                  GOOGLE_MOCK_HOSTED_DOMAIN=vtc.vn.ua turns the domain rule on;
#                                  --lan opens the stand to phones on the local network: https://<this PC's IP>
#                                  through Caddy with a local CA (infra/docker-compose.lan.yml); the address is
#                                  detected, or given as --lan=<ip>; sign-in then works only at that address
#   scripts/dev-stack.sh down      stop the app services (infrastructure and data stay)
#   scripts/dev-stack.sh down --all   stop everything incl. postgres/rabbitmq/redis (data kept in volumes)
#   scripts/dev-stack.sh status    containers, health, memory, URLs
#   scripts/dev-stack.sh seed [step ...]   real data through the API (scripts/seed_demo.py)
#   scripts/dev-stack.sh reset-data --yes  empty the core/importer/scheduler/notification databases
#                                  (accounts in auth stay); then `up` and `seed` again
#   scripts/dev-stack.sh reset-auth --yes  drop and recreate the auth database (accounts, sessions);
#                                  the next `up` creates the demo accounts again
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

# The compose network has a fixed ip_range (auth trusts X-Forwarded-For only from it). A network created
# before that cannot be changed in place, and compose cannot remove it while containers use it: stop the
# stack once (data stays in volumes) so the next `up` creates the network anew.
recreate_old_network() {
  local ranges
  ranges="$(docker network inspect rozklad_default --format '{{range .IPAM.Config}}{{.IPRange}} {{end}}' 2>/dev/null)"     || return 0
  if [[ "$ranges" != *172.29.1.0/24* ]]; then
    echo "== the compose network has no fixed ip_range: stopping the stack once to recreate it"
    docker compose "${env_args[@]}" "${compose_files[@]}" --profile app --profile infra down
  fi
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

# The IPv4 address of this machine on the local network: the interface with the default route.
detect_lan_ip() {
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "(Get-NetIPConfiguration | Where-Object { \$_.IPv4DefaultGateway -and \$_.NetAdapter.Status -eq 'Up' } | Select-Object -First 1).IPv4Address.IPAddress" 2>/dev/null |
      tr -d '\r' | head -1
  elif command -v ip >/dev/null 2>&1; then
    ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1
  fi
}

# auth treats an https issuer as development only on a private IPv4 address (ROZKLAD_LAN_DEV)
is_private_ipv4() {
  [[ $1 =~ ^(10\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])|192\.168)\.[0-9]{1,3}\.[0-9]{1,3}$ ]]
}

lan_ca=infra/dev/certs/rozklad-dev-ca.crt

# The root of Caddy's local CA, for phones to trust: Caddy creates it with the first certificate.
export_lan_ca() {
  local deadline=$((SECONDS + 60))
  mkdir -p "$(dirname "$lan_ca")"
  until docker cp rozklad-lan-edge-1:/data/caddy/pki/authorities/local/root.crt "$lan_ca" >/dev/null 2>&1; do
    if ((SECONDS > deadline)); then
      echo "  lan-edge: no local CA after 60 s — see: docker logs rozklad-lan-edge-1" >&2
      return 1
    fi
    sleep 2
  done
  echo "  lan-edge: local CA root copied to $lan_ca"
}

# The stand through Caddy the way a phone reaches it: the certificate verifies against the local CA, discovery
# names the LAN issuer, and an authorize request without a session lands on /auth/login of the same origin.
lan_smoke() {
  local base="https://$1" issuer location
  issuer=$(curl -fsS --cacert "$lan_ca" "$base/auth/.well-known/openid-configuration" |
    sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p')
  if [[ $issuer != "$base/auth" ]]; then
    echo "  lan smoke: discovery issuer is '$issuer', expected $base/auth" >&2
    return 1
  fi
  location=$(curl -sS --cacert "$lan_ca" -o /dev/null -w '%{redirect_url}' -H 'Accept: text/html' \
    "$base/auth/oauth2/authorize?response_type=code&client_id=rozklad-web&scope=openid&state=smoke&redirect_uri=https%3A%2F%2F$1%2Flogin%2Fcallback&code_challenge=47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU&code_challenge_method=S256")
  if [[ $location != "$base/auth/login" ]]; then
    echo "  lan smoke: authorize without a session redirects to '$location', expected $base/auth/login" >&2
    return 1
  fi
  echo "  lan: certificate, discovery and sign-in redirect through Caddy are correct"
}

# The Caddy of an earlier `up --lan`: stopped when the stack comes up or goes down without it.
stop_lan_edge() {
  if docker ps -q --filter name=rozklad-lan-edge-1 | grep -q .; then
    docker stop rozklad-lan-edge-1 >/dev/null
    echo "  lan-edge: stopped (https://<IP> is closed)"
  fi
}

# The mock of an earlier `up --google-mock`: stopped when the stack comes up without it.
stop_google_mock() {
  if docker ps -q --filter name=rozklad-mock-oidc-1 | grep -q .; then
    docker stop rozklad-mock-oidc-1 >/dev/null
    echo "  mock-oidc: stopped (started without --google-mock)"
  fi
}

cmd_up() {
  local build=1 web=1 telegram=${TELEGRAM:-on} google_mock=0 time_flag=0 stand_time="" lan=0 lan_ip=""
  while (($#)); do
    case $1 in
      --google-mock) google_mock=1 ;;
      --no-telegram) telegram=off ;;
      --no-build) build=0 ;;
      --web) web=1 ;;
      --no-web) web=0 ;;
      --time) time_flag=1; stand_time=${2:-}; (($# > 1)) && shift ;;
      --time=*) time_flag=1; stand_time=${1#--time=} ;;
      --lan) lan=1 ;;
      --lan=*) lan=1; lan_ip=${1#--lan=} ;;
      *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
  done
  if ((time_flag)) && [[ ! $stand_time =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2})?$ ]]; then
    echo "--time needs a Kyiv date-time like 2026-11-20T10:00, got '$stand_time'" >&2
    exit 2
  fi
  if ((lan)); then
    ((web)) || { echo "--lan needs the web container (Caddy passes everything to it)" >&2; exit 2; }
    [[ -n $lan_ip ]] || lan_ip=$(detect_lan_ip)
    if ! is_private_ipv4 "$lan_ip"; then
      echo "--lan needs this PC's private IPv4 address (10.x, 172.16-31.x, 192.168.x), got '$lan_ip';" \
        "give it as --lan=<ip>" >&2
      exit 2
    fi
    export ROZKLAD_LAN_IP=$lan_ip
  fi
  [[ $telegram == off ]] && compose_files+=(-f infra/docker-compose.no-telegram.yml)
  ((google_mock)) && compose_files+=(-f infra/docker-compose.google-mock.yml)
  ((lan)) && compose_files+=(-f infra/docker-compose.lan.yml)
  local targets=("${services[@]}")
  ((web)) && targets+=(web)
  ((lan)) && targets+=(lan-edge)
  ((google_mock)) || stop_google_mock
  ((lan)) || stop_lan_edge

  recreate_old_network
  echo "== infrastructure"
  # --remove-orphans: containers of services that are no longer in the compose files
  docker compose "${env_args[@]}" "${compose_files[@]}" --profile infra up -d --remove-orphans
  wait_healthy postgres rabbitmq redis
  ensure_databases

  if ((build)); then
    echo "== jars (mvn package, tests skipped)"
    # shellcheck source=env.sh
    source "$root/scripts/env.sh"
    mvn -q -DskipTests package -pl "$(printf 'services/%s,' "${services[@]}" | sed 's/,$//')" -am
    echo "== images"
    for service in "${targets[@]}"; do
      [[ $service == lan-edge ]] && continue # a stock image
      compose build "$service"
    done
  fi
  # stand time: one anchor (the real moment the stand time refers to) for every service, so their clocks agree;
  # without --time the variables are empty and override a value left in .env
  export ROZKLAD_STAND_TIME=$stand_time ROZKLAD_STAND_TIME_ANCHOR=""
  [[ -n $stand_time ]] && ROZKLAD_STAND_TIME_ANCHOR=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  echo "== services (telegram: $telegram, google mock: $( ((google_mock)) && echo on || echo off)," \
    "time: ${stand_time:-real}, lan: ${lan_ip:-off})"
  ((google_mock)) && compose up -d mock-oidc
  compose up -d --no-build "${targets[@]}"
  wait_healthy "${services[@]}"
  if ((web)) && ((lan)); then
    wait_healthy lan-edge
    export_lan_ca
    lan_smoke "$lan_ip"
  elif ((web)); then
    wait_healthy web
    auth_smoke
  fi
  cmd_status
  if ((google_mock)); then
    echo "Mock Google     http://localhost:8090/google   (any name, claims e.g. {\"email\":\"krentsin@rozklad.local\"})"
  fi
}

# The phone stand: the address, the CA a phone has to trust, and the one firewall rule it needs.
lan_status() {
  local ip
  docker ps -q --filter name=rozklad-lan-edge-1 | grep -q . || return 0
  ip=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' rozklad-lan-edge-1 | sed -n 's/^LAN_IP=//p')
  cat <<EOF

PHONE STAND     https://$ip   (sign-in works only here; http://localhost:8088 shows the site without sign-in)
Local CA        $lan_ca   (trust it once on the phone: docs/RUNNING.md «Phone on the LAN»)
Firewall        once, in an elevated PowerShell: New-NetFirewallRule -DisplayName "Rozklad LAN stand (443)"
                -Direction Inbound -Protocol TCP -LocalPort 443 -Profile Private -Action Allow
EOF
}

cmd_down() {
  if [[ ${1:-} == --all ]]; then
    docker compose "${env_args[@]}" "${compose_files[@]}" --profile app --profile infra stop
  else
    compose stop "${services[@]}" web
  fi
  stop_google_mock
  stop_lan_edge
}

# The stand time core runs with (empty = real time) and, when core already reports it, the current stand time.
stand_time_status() {
  local env standing anchor now
  env=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' rozklad-core-1 2>/dev/null) || return 0
  standing=$(sed -n 's/^ROZKLAD_STAND_TIME=//p' <<<"$env")
  if [[ -z $standing ]]; then
    echo "Time            real (scripts/dev-stack.sh up --no-build --time 2026-11-20T10:00 shifts it)"
    return 0
  fi
  anchor=$(sed -n 's/^ROZKLAD_STAND_TIME_ANCHOR=//p' <<<"$env")
  now=$(curl -fsS --max-time 3 http://127.0.0.1:8080/api/public/meta 2>/dev/null |
    sed -n 's/.*"now":"\([^"]*\)".*/\1/p')
  echo "Time            STAND TIME: $standing Kyiv at ${anchor:-the start of core} (real, UTC);" \
    "on the stand now ${now:-unknown} (UTC); 'up' without --time returns to real time"
}

cmd_status() {
  docker ps --filter name=rozklad- --format 'table {{.Names}}\t{{.Status}}'
  echo
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}' $(docker ps -q --filter name=rozklad-) 2>/dev/null || true
  echo
  stand_time_status
  lan_status
  cat <<'EOF'

API (gateway)   http://localhost:8080   e.g. /api/public/meta, /api/public/schedule/group/{id}
core            http://localhost:8081/swagger-ui.html      scheduler :8082  importer :8083  notification :8084
Site            http://localhost:8088
Sign-in         http://localhost:8088/auth/login   (service auth; host-run services: http://localhost:8085/auth)
Token (scripts) python scripts/rozklad_auth.py token --user admin
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

cmd_reset_auth() {
  [[ ${1:-} == --yes ]] || { echo "reset-auth deletes every account, session and sign-in of auth; add --yes" >&2; exit 2; }
  compose stop auth
  docker exec rozklad-postgres-1 psql -q -U postgres -d postgres -c "DROP DATABASE IF EXISTS auth WITH (FORCE);"
  ensure_databases
  echo "Done. Next: scripts/dev-stack.sh up --no-build (the demo accounts are created again)"
}

case ${1:-} in
  up) shift; cmd_up "$@" ;;
  down) shift; cmd_down "$@" ;;
  status) cmd_status ;;
  seed) shift; PYTHONIOENCODING=utf-8 "$(python_cmd)" "$root/scripts/seed_demo.py" "$@" ;;
  reset-data) shift; cmd_reset_data "$@" ;;
  reset-auth) shift; cmd_reset_auth "$@" ;;
  logs) shift; compose logs -f --tail=200 "${1:?service}" ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
