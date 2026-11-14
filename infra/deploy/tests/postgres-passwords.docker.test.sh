#!/usr/bin/env bash
# infra/deploy/postgres/20-prod-passwords.sh in a throwaway container of the stack's PostgreSQL image,
# initialised like production (10-init.sql, then the script). restore-db.sh runs the script again after
# loading a dump; a pg_dumpall dump carries the old cluster's password of the superuser too, so the
# script must set every password from the environment, the superuser's included.
# shellcheck source-path=SCRIPTDIR
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
init_sql=$(host_path "$(cd "$here/../../postgres" && pwd)/init.sql")
prod_sh=$(host_path "$(cd "$here/../postgres" && pwd)/20-prod-passwords.sh")
image=$(sed -n 's/^ *image: \(postgres:.*\)$/\1/p' "$here/../../docker-compose.yml" | head -n 1)
container="rozklad-postgres-test-$$"
pw() { printf 'test-%s-%s' "$1" "$$"; }

MSYS_NO_PATHCONV=1 docker run -d --name "$container" \
  -e POSTGRES_PASSWORD="$(pw postgres)" -e CORE_DB_PASSWORD="$(pw core)" -e SCHEDULER_DB_PASSWORD="$(pw scheduler)" \
  -e IMPORTER_DB_PASSWORD="$(pw importer)" -e NOTIFICATION_DB_PASSWORD="$(pw notification)" -e AUTH_DB_PASSWORD="$(pw auth)" \
  -v "$init_sql:/docker-entrypoint-initdb.d/10-init.sql:ro" \
  -v "$prod_sh:/docker-entrypoint-initdb.d/20-prod-passwords.sh:ro" \
  "$image" >/dev/null || { echo "cannot start $image"; exit 1; }
trap 'docker rm -f -v "$container" >/dev/null 2>&1' EXIT

# signs in over TCP to the container's own address, as the services do: the image trusts the unix
# socket and 127.0.0.1 without a password, every other address needs one (scram-sha-256)
signs_in() {
  MSYS_NO_PATHCONV=1 docker exec -e PGPASSWORD="$2" "$container"     sh -c 'psql -h "$(hostname -i | cut -d" " -f1)" -U "$0" -d postgres -tAc "select 1"' "$1" >/dev/null 2>&1
}
for _ in $(seq 1 90); do
  signs_in postgres "$(pw postgres)" && break
  sleep 1
done

check "first start: the superuser has POSTGRES_PASSWORD" signs_in postgres "$(pw postgres)"
check "first start: core has CORE_DB_PASSWORD, not the dev password" signs_in core "$(pw core)"
detail="core still accepts the development password"
check "first start: the dev password of core is gone" eval '! signs_in core core'

# what loading a dump of another cluster does, then what restore-db.sh runs
MSYS_NO_PATHCONV=1 docker exec "$container" psql -q -U postgres -d postgres \
  -c "ALTER ROLE postgres WITH PASSWORD 'old-cluster'" -c "ALTER ROLE core WITH PASSWORD 'old-cluster'"
detail=$(MSYS_NO_PATHCONV=1 docker exec "$container" bash /docker-entrypoint-initdb.d/20-prod-passwords.sh 2>&1)
check "after a restore: the script runs" test $? = 0
check "after a restore: core has CORE_DB_PASSWORD again" signs_in core "$(pw core)"
detail="the superuser kept the password of the dumped cluster"
check "after a restore: the superuser has POSTGRES_PASSWORD again" signs_in postgres "$(pw postgres)"
check "after a restore: the dumped superuser password is gone" eval '! signs_in postgres old-cluster'

finish
