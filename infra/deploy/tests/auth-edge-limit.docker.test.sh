#!/usr/bin/env bash
# Sign-in through the production edge, in throwaway containers: Caddy (the production Caddyfile, https
# with its internal CA) -> the web nginx (web/nginx.conf) -> a stub auth that answers with the forwarding
# headers it got. The throwaway network is laid out like the compose network: containers get addresses
# from its ip_range, connections to published ports come through its gateway, outside that range.
#
#   - auth sees the client address Caddy saw, https and port 443, never the client's own forwarding headers
#   - above the per-address limit of /auth/* a client gets 429, forged forwarding headers give it no new
#     bucket, and the query strings of the refused requests reach no log
#   - the development path (nginx without Caddy) is not limited and ignores client forwarding headers
#
# NGINX_IMAGE overrides the image (default: the runtime stage of web/Dockerfile); EDGE_TEST_NET overrides
# the throwaway network as "subnet ip_range gateway" when its default range is taken on this machine.
# shellcheck source-path=SCRIPTDIR
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

repo="$(cd "$here/../../.." && pwd)"
read -r subnet range gateway <<<"${EDGE_TEST_NET:-10.231.0.0/16 10.231.1.0/24 10.231.0.1}"
compose_range=$(sed -n 's/^ *ip_range: *\([0-9./]*\).*$/\1/p' "$repo/infra/docker-compose.yml" | head -n 1)
caddy_image=$(sed -n 's/^ *image: \(caddy:.*\)$/\1/p' "$here/../compose.prod.yml" | head -n 1)
nginx_image=${NGINX_IMAGE:-$(sed -n 's/^FROM \(nginx:[^ ]*\) AS runtime$/\1/p' "$repo/web/Dockerfile" | head -n 1)}
name="rozklad-edge-test-$$"
work=$(mktemp -d "${TMPDIR:-/tmp}/rozklad-edge-test.XXXXXX")
cleanup() {
  docker rm -f "$name-caddy" "$name-web" "$name-auth" >/dev/null 2>&1
  docker network rm "$name" >/dev/null 2>&1
  rm -rf "$work"
}
trap cleanup EXIT

# nginx trusts forwarding headers from the compose network's container range; in this test that is the
# range of the throwaway network
detail="web/nginx.conf does not trust the compose network's ip_range '$compose_range'"
check "web/nginx.conf trusts forwarding headers from the compose containers only" \
  grep -q "^set_real_ip_from $compose_range;$" "$repo/web/nginx.conf"
mkdir -p "$work/web" "$work/auth"
sed "s#$compose_range#$range#g" "$repo/web/nginx.conf" >"$work/web/default.conf"
cat >"$work/auth/Caddyfile" <<'EOF'
:8085 {
	respond "xff={header.X-Forwarded-For} proto={header.X-Forwarded-Proto} port={header.X-Forwarded-Port} host={header.X-Forwarded-Host} ." 200
}
EOF
caddy_dir="$repo/infra/deploy/caddy"
web_conf="$work/web/default.conf"
auth_dir="$work/auth"
if command -v cygpath >/dev/null 2>&1; then
  caddy_dir=$(cygpath -w "$caddy_dir") web_conf=$(cygpath -w "$web_conf") auth_dir=$(cygpath -w "$auth_dir")
fi

docker network create --subnet "$subnet" --ip-range "$range" --gateway "$gateway" "$name" >/dev/null ||
  { echo "cannot create the network $subnet (taken here? set EDGE_TEST_NET)"; exit 1; }
MSYS_NO_PATHCONV=1 docker run -d --rm --name "$name-auth" --network "$name" --network-alias auth \
  -v "$auth_dir:/etc/caddy:ro" "$caddy_image" >/dev/null || { echo "cannot start the stub auth"; exit 1; }
MSYS_NO_PATHCONV=1 docker run -d --rm --name "$name-web" --network "$name" --network-alias web \
  -p 127.0.0.1::80 -v "$web_conf:/etc/nginx/conf.d/default.conf:ro" "$nginx_image" >/dev/null ||
  { echo "cannot start $nginx_image"; exit 1; }
MSYS_NO_PATHCONV=1 docker run -d --rm --name "$name-caddy" --network "$name" \
  -e DOMAIN=caddy.localhost -e ACME_EMAIL=ci@example.org -e WEB_UPSTREAM=web:80 \
  -p 127.0.0.1::443 -v "$caddy_dir:/etc/caddy:ro" "$caddy_image" >/dev/null || { echo "cannot start $caddy_image"; exit 1; }
web_port=$(docker port "$name-web" 80/tcp | head -n 1 | sed 's/.*://')
edge_port=$(docker port "$name-caddy" 443/tcp | head -n 1 | sed 's/.*://')

# Through Caddy as a browser reaches it (https, Host without a port), and directly as in development
edge_url="https://caddy.localhost:$edge_port"
edge_args=(-k --resolve "caddy.localhost:$edge_port:127.0.0.1" -H 'Host: caddy.localhost')
direct_url="http://127.0.0.1:$web_port"
direct_args=(-H 'Host: localhost:8088')
edge() { curl -s --http1.1 --max-time 15 "${edge_args[@]}" "${@:2}" "$edge_url$1"; }
direct() { curl -s --http1.1 --max-time 15 "${direct_args[@]}" "${@:2}" "$direct_url$1"; }
for _ in $(seq 1 75); do
  [[ $(edge /auth/login -o /dev/null -w '%{http_code}') == 200 &&
    $(direct /auth/login -o /dev/null -w '%{http_code}') == 200 ]] && break
  sleep 0.2
done

forged=(-H 'X-Forwarded-For: 203.0.113.7' -H 'Forwarded: for=203.0.113.8' -H 'X-Real-IP: 203.0.113.9'
  -H 'X-Forwarded-Port: 8443' -H 'X-Forwarded-Host: evil.example')
edge_seen=$(edge /auth/login "${forged[@]}" -H 'X-Forwarded-Proto: http')
direct_seen=$(direct /auth/login "${forged[@]}" -H 'X-Forwarded-Proto: https')
field() { sed -n "s/.*$1=\([^ ]*\) .*/\1/p" <<<"$2"; }
# Connections to published ports come from the gateway of the network: "the client" of this test
client=$(field xff "$direct_seen")
container_prefix="${range%.*}."

direct_passes_the_connection() { [[ -n $client && $client != 203.0.113.* && $client != "$container_prefix"* ]]; }
detail="directly: $direct_seen (containers: $range)"
check "directly: auth sees the address of the connection, not the client's X-Forwarded-For" direct_passes_the_connection
check "directly: the scheme of the connection and the port of Host (localhost:8088)" \
  grep -q 'proto=http port=8088 host=localhost:8088 ' <<<"$direct_seen"

edge_passes_the_client() { [[ $(field xff "$edge_seen") == "$client" ]]; }
detail="through Caddy: $edge_seen (directly: $direct_seen)"
check "through Caddy: auth sees the address Caddy saw, not Caddy's own and not a forged one" edge_passes_the_client
check "through Caddy: auth sees https, port 443 and the site's host" \
  grep -q 'proto=https port=443 host=caddy.localhost ' <<<"$edge_seen"

# One client, many requests at once, each with its own forged address and a secret-looking query string
flood() { # flood DIR URL CURL_ARGS...: the responses in DIR/<n>.head and DIR/<n>.body
  local out=$1 url=$2
  shift 2
  mkdir -p "$out"
  seq 1 120 | xargs -P 40 -I{} curl -s --http1.1 --max-time 15 "$@" \
    -H 'X-Forwarded-For: 198.51.100.{}' -H 'Forwarded: for=198.51.100.{}' -D "$out/{}.head" -o "$out/{}.body" \
    "$url/auth/login/oauth2/code/google?code=sekret-code-{}&state=s{}" >/dev/null 2>&1
}
answered() { grep -l "^HTTP/1.1 $2" "$1"/*.head 2>/dev/null | wc -l | tr -d ' '; }

flood "$work/edge" "$edge_url" "${edge_args[@]}"
limited=$(answered "$work/edge" 429)
passed=$(answered "$work/edge" 200)
edge_is_limited() { ((limited >= 30 && passed >= 21)); }
detail="$limited of 120 requests got 429, $passed got 200"
check "through Caddy: one client gets 429 above the limit, forged forwarding headers do not help" edge_is_limited

refused=$(grep -l '^HTTP/1.1 429' "$work/edge"/*.head 2>/dev/null | head -n 1)
refused=${refused%.head}
refusal_is_explained() {
  [[ -n $refused ]] && grep -qi '^retry-after: 5' "$refused.head" && grep -q 'Забагато запитів' "$refused.body"
}
detail=$(cat "$refused.head" "$refused.body" 2>/dev/null)
check "the refusal is a Ukrainian text with Retry-After" refusal_is_explained

logs() { docker logs "$name-web" 2>&1; docker logs "$name-caddy" 2>&1; }
no_query_in_logs() { ! logs | grep -q sekret-code; }
detail=$(logs | grep -m 3 sekret-code)
check "no log line of nginx or Caddy carries the query string of a request" no_query_in_logs

flood "$work/direct" "$direct_url" "${direct_args[@]}"
limited=$(answered "$work/direct" 429)
passed=$(answered "$work/direct" 200)
direct_is_not_limited() { ((limited == 0 && passed == 120)); }
detail="$limited of 120 direct requests got 429, $passed got 200"
check "directly (development): the sign-in is not limited" direct_is_not_limited

finish
