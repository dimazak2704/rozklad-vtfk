#!/usr/bin/env bash
# Запускає один сервіс на хості зі змінними з .env (секрети) поверх типових значень application.yml.
#   scripts/run-local.sh core|scheduler|importer|notification|gateway [extra JVM args...]
# Спочатку зберіть jar:  mvn -q -pl services/<service> -am package -DskipTests
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
service="${1:?usage: scripts/run-local.sh <core|scheduler|importer|notification|gateway>}"
shift

# shellcheck source=env.sh
source "$root/scripts/env.sh"

jar="$root/services/$service/target/app.jar"
if [[ ! -f "$jar" ]]; then
  echo "Немає $jar — зберіть: mvn -q -pl services/$service -am package -DskipTests" >&2
  exit 1
fi

if [[ -f "$root/.env" ]]; then
  # .env може мати CRLF (Windows): прибираємо \r, порожні значення не перекривають типові з application.yml
  while IFS='=' read -r key value; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    value="${value%$'\r'}"
    [[ -n "$value" ]] && export "$key=$value"
  done < "$root/.env"
fi

exec java "$@" -jar "$jar"
