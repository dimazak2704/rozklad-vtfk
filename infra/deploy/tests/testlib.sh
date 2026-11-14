# Helpers of the deployment script tests. Source this file, do not run it.
# shellcheck shell=bash disable=SC2034 # status and detail are read by the tests
#
# The tests copy the scripts under test into a throwaway directory laid out like the repository and
# put stub commands (docker, age, rclone, curl) first in PATH, so nothing touches Docker, the network
# or a real server. SCRIPTS_DIR points at another copy of infra/deploy/scripts (for example an older
# commit) to see a test fail without a fix.

tests_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="${SCRIPTS_DIR:-$tests_here/../scripts}"

tests_run=0
tests_failed=0
# set by run_script and by the tests, read by check
status=0
detail=''

pass() {
  tests_run=$((tests_run + 1))
  printf 'ok    %s\n' "$1"
}

fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  printf 'FAIL  %s\n' "$1"
  if [[ -n ${2:-} ]]; then
    printf '%s\n' "$2" | sed 's/^/      /'
  fi
}

# check NAME CONDITION...: passes when the command succeeds
check() {
  local name=$1
  shift
  if "$@"; then pass "$name"; else fail "$name" "${detail:-}"; fi
}

finish() {
  printf '%s: %d of %d checks failed\n' "$(basename "$0")" "$tests_failed" "$tests_run"
  ((tests_failed == 0))
}

# new_sandbox: a fresh directory with bin/ (first in PATH for the scripts) and the repository layout
# repo/infra/deploy/scripts with lib.sh. Prints the directory.
new_sandbox() {
  local sb
  sb=$(mktemp -d "${TMPDIR:-/tmp}/rozklad-deploy-test.XXXXXX")
  mkdir -p "$sb/bin" "$sb/repo/infra/deploy/scripts" "$sb/log"
  cp "$SCRIPTS_DIR/lib.sh" "$sb/repo/infra/deploy/scripts/"
  printf '%s' "$sb"
}

# stub SANDBOX NAME BODY: an executable bin/NAME with the given bash body
stub() {
  printf '#!/usr/bin/env bash\n%s\n' "$3" >"$1/bin/$2"
  chmod +x "$1/bin/$2"
}

# run_script SANDBOX SCRIPT [ARGS...]: runs repo/infra/deploy/scripts/SCRIPT with the stubs first in
# PATH; stdout and stderr go to log/out and log/err, the exit code to $status. On Git Bash for Windows
# require_server needs ROZKLAD_ALLOW_LOCAL and another compose project name.
run_script() {
  local sb=$1 script=$2
  shift 2
  status=0
  env -u DOMAIN -u ROZKLAD_ENV_FILE -u ROZKLAD_IMAGES_FILE -u IMAGE_PREFIX -u IMAGE_TAG \
    PATH="$sb/bin:$PATH" STUB_LOG="$sb/log" ROZKLAD_ALLOW_LOCAL=1 COMPOSE_PROJECT_NAME=rozklad-test \
    bash "$sb/repo/infra/deploy/scripts/$script" "$@" <"${STDIN_FILE:-/dev/null}" \
    >"$sb/log/out" 2>"$sb/log/err" || status=$?
}
