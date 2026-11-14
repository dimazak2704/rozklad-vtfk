#!/usr/bin/env bash
# Routing of the production Caddyfile, in a throwaway Caddy container (the image of compose.prod.yml).
# The site runs on plain http here; gateway, auth and web do not exist, so a proxied request answers
# 502 and a path Caddy closes itself answers 404. Service-to-service endpoints (/api/internal/**, the
# bot's parse-absence) and auth's internal API must never be proxied from the internet.
# shellcheck source-path=SCRIPTDIR
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

caddy_dir="$(cd "${CADDY_DIR:-$here/../caddy}" && pwd)"
mount=$caddy_dir
if command -v cygpath >/dev/null 2>&1; then mount=$(cygpath -w "$caddy_dir"); fi
image=$(sed -n 's/^ *image: \(caddy:.*\)$/\1/p' "$here/../compose.prod.yml" | head -n 1)
name="rozklad-caddy-test-$$"

MSYS_NO_PATHCONV=1 docker run -d --rm --name "$name" \
  -e DOMAIN=http://caddy.test -e ACME_EMAIL=ci@example.org \
  -p 127.0.0.1::80 -v "$mount:/etc/caddy:ro" "$image" >/dev/null || { echo "cannot start $image"; exit 1; }
trap 'docker rm -f "$name" >/dev/null 2>&1' EXIT
port=$(docker port "$name" 80/tcp | head -n 1 | sed 's/.*://')

code() { curl -s -o /dev/null --path-as-is --max-time 10 -w '%{http_code}' -H 'Host: caddy.test' "http://127.0.0.1:$port$1"; }
for _ in $(seq 1 50); do
  [[ $(code /) != 000 ]] && break
  sleep 0.2
done

closed() { detail="answered $(code "$1")"; [[ $(code "$1") == 404 ]]; }
proxied() { detail="answered $(code "$1")"; [[ $(code "$1") == 502 ]]; }

for path in /api/internal /api/internal/ /api/internal/teachers/x /api/internal/changes/x/reply \
  /API/Internal/catalog/snapshot /api/%69nternal/absences /api//internal/absences \
  /api/import/ai/parse-absence /api/import/ai/parse-absence/ /Api/Import/AI/Parse-Absence \
  /auth/internal /auth/internal/accounts /AUTH/Internal/accounts /auth/%69nternal/accounts \
  /auth//internal/accounts; do
  check "closed at the edge: $path" closed "$path"
done
for path in /api/public/meta /api/events/stream /api/import/ai/budget /api/internals /auth/login /; do
  check "still proxied: $path" proxied "$path"
done

finish
