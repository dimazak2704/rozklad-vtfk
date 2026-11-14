#!/usr/bin/env bash
# The production broker user (RABBITMQ_DEFAULT_USER + infra/deploy/rabbitmq/20-rozklad.conf), in a
# throwaway container of the stack's RabbitMQ image: it authenticates (the services use AMQP), but it
# has no tag, so the management HTTP API refuses it.
# shellcheck source-path=SCRIPTDIR
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

conf="$(cd "$here/../rabbitmq" && pwd)/20-rozklad.conf"
if command -v cygpath >/dev/null 2>&1; then conf=$(cygpath -w "$conf"); fi
image=$(sed -n 's/^ *image: \(rabbitmq:.*\)$/\1/p' "$here/../../docker-compose.yml" | head -n 1)
container="rozklad-rabbitmq-test-$$"
user=rozklad
pass="test-$$-$RANDOM$RANDOM"

MSYS_NO_PATHCONV=1 docker run -d --name "$container" -e RABBITMQ_DEFAULT_USER="$user" -e RABBITMQ_DEFAULT_PASS="$pass" \
  -v "$conf:/etc/rabbitmq/conf.d/20-rozklad.conf:ro" "$image" >/dev/null || { echo "cannot start $image"; exit 1; }
trap 'docker rm -f -v "$container" >/dev/null 2>&1' EXIT
# wait on the log: a CLI call as root before the start would create the Erlang cookie as root
for _ in $(seq 1 90); do
  docker logs "$container" 2>&1 | grep -q 'Server startup complete' && break
  sleep 1
done
as_broker() { MSYS_NO_PATHCONV=1 docker exec -u rabbitmq "$container" "$@"; }

detail=$(as_broker rabbitmqctl -q list_users 2>&1)
check "the user exists and has no tags" grep -Eq "^${user}[[:space:]]+\[\]$" <<<"$detail"
authenticates() { as_broker rabbitmqctl -q authenticate_user "$user" "$pass" >/dev/null 2>&1; }
detail="rabbitmqctl authenticate_user failed"
check "the user still authenticates (AMQP)" authenticates
management_refuses() {
  ! MSYS_NO_PATHCONV=1 docker exec "$container" sh -c \
    "wget -q -O /dev/null --header \"Authorization: Basic \$(printf '%s:%s' '$user' '$pass' | base64)\" http://127.0.0.1:15672/api/whoami" 2>/dev/null
}
detail="the management API accepted the user"
check "the management API refuses the user" management_refuses

finish
