#!/usr/bin/env bash
# deploy.sh preflight: the first admin's password must not stay in .env (and in the auth container's
# environment) unnoticed. docker is a stub that lets preflight pass and stops the run at the build.
# shellcheck source-path=SCRIPTDIR disable=SC2016 # stub bodies are single-quoted on purpose
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

setup() {
  sb=$(new_sandbox)
  cp "$SCRIPTS_DIR/deploy.sh" "$sb/repo/infra/deploy/scripts/"
  mkdir -p "$sb/repo/infra/deploy/secrets"
  printf -- '-----BEGIN PRIVATE KEY-----\nstub\n-----END PRIVATE KEY-----\n' >"$sb/repo/infra/deploy/secrets/auth-signing-key.pem"
  stub "$sb" docker '
printf "%s\n" "$*" >>"$STUB_LOG/docker"
case "$1 ${2:-}" in
  "compose version") echo 2.30.0 ;;
  "run "*) exit 1 ;; # the jar build: preflight is over, stop here
esac
exit 0'
}

env_file() {
  printf '%s\n' DOMAIN=rozklad.example.org ACME_EMAIL=admin@rozklad.example.org "$@" >"$sb/repo/infra/deploy/.env"
}

preflight() {
  run_script "$sb" deploy.sh --no-pull
  detail="exit $status; stdout: $(cat "$sb/log/out"); stderr: $(cat "$sb/log/err")"
}

setup
env_file AUTH_BOOTSTRAP_ADMIN_PASSWORD=first-admin-password-123
preflight
check "preflight passes and the run stops at the stubbed build" grep -q 'preflight:' "$sb/log/out"
check "a bootstrap password left in .env is a warning on stderr" \
  grep -Eq '^WARN.*AUTH_BOOTSTRAP_ADMIN_PASSWORD' "$sb/log/err"
rm -rf "$sb"

setup
env_file AUTH_BOOTSTRAP_ADMIN_PASSWORD=
preflight
check "no warning once the bootstrap password is empty" eval '! grep -q AUTH_BOOTSTRAP_ADMIN_PASSWORD "$sb/log/err"'
rm -rf "$sb"

finish
