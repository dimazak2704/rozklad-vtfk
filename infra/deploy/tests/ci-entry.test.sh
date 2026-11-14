#!/usr/bin/env bash
# ci-entry.sh is the forced command of the GitHub deploy key. Whoever holds that key chooses every
# stdin line, so the server must decide which images may run: only its own GHCR owner and only the
# commit at the tip of origin/main. deploy.sh and docker are stubs here.
# shellcheck source-path=SCRIPTDIR disable=SC2016 # stub bodies are single-quoted on purpose
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

g() {
  git -C "$sb/repo" -c user.name=test -c user.email=test@example.org -c commit.gpgsign=false \
    -c core.hooksPath=/dev/null -c core.autocrlf=false "$@"
}

# sandbox with origin.git (main: old_sha -> main_sha, feature: feature_sha) and a clone in repo/
setup() {
  sb=$(new_sandbox)
  cp "$SCRIPTS_DIR/ci-entry.sh" "$sb/repo/infra/deploy/scripts/"
  cat >"$sb/repo/infra/deploy/scripts/deploy.sh" <<'STUB'
printf '%s %s %s\n' "$IMAGE_PREFIX" "$IMAGE_TAG" "$*" >>"$STUB_LOG/deploy"
STUB
  stub "$sb" docker 'printf "%s\n" "$*" >>"$STUB_LOG/docker"; if [[ $1 == login ]]; then cat >/dev/null; fi'
  printf 'infra/deploy/.env\n' >"$sb/repo/.gitignore"
  git init -q --bare "$sb/origin.git"
  git -C "$sb/origin.git" symbolic-ref HEAD refs/heads/main
  g init -q
  g checkout -q -b main
  g add -A
  g commit -q -m one
  old_sha=$(g rev-parse HEAD)
  g commit -q --allow-empty -m two
  main_sha=$(g rev-parse HEAD)
  g checkout -q -b feature
  g commit -q --allow-empty -m three
  feature_sha=$(g rev-parse HEAD)
  g checkout -q main
  g remote add origin "$sb/origin.git"
  g push -q origin main feature 2>/dev/null
}

# origin_url URL: origin claims to be URL (a GitHub address), git still fetches from origin.git
origin_url() {
  local local_path
  local_path=$(cygpath -m "$sb/origin.git" 2>/dev/null || printf '%s' "$sb/origin.git")
  g config remote.origin.url "$1"
  g config "url.$local_path.insteadOf" "$1"
}

env_file() { printf '%s\n' "$@" >"$sb/repo/infra/deploy/.env"; }

# deploy_with TAG PREFIX [DOMAIN]: the five stdin lines of the workflow
deploy_with() {
  printf '%s\n' "$1" "$2" deploy-bot ghs_token "${3:-rozklad.example.org}" >"$sb/log/stdin"
  rm -f "$sb/log/deploy" "$sb/log/docker"
  STDIN_FILE="$sb/log/stdin" run_script "$sb" ci-entry.sh
  detail="exit $status; stderr: $(cat "$sb/log/err")"
}

deployed() { [[ $status == 0 && $(cat "$sb/log/deploy" 2>/dev/null) == "$1 $2 --pull-images" ]]; }
refused() { [[ $status != 0 && ! -e $sb/log/deploy ]] && ! grep -q '^login' "$sb/log/docker" 2>/dev/null; }

# ---- prefix pinned in .env -------------------------------------------------------------------------
setup
env_file DOMAIN=rozklad.example.org DEPLOY_IMAGE_PREFIX=ghcr.io/dimazak2704/rozklad

deploy_with "$main_sha" ghcr.io/dimazak2704/rozklad
check "deploys the tip of main with the pinned prefix" deployed ghcr.io/dimazak2704/rozklad "$main_sha"

deploy_with "$main_sha" ghcr.io/evil/rozklad
check "refuses images of another GHCR owner, before logging in" refused

deploy_with "$old_sha" ghcr.io/dimazak2704/rozklad
check "refuses an older commit of main (no rollback through the key)" refused

deploy_with "$feature_sha" ghcr.io/dimazak2704/rozklad
check "refuses a commit that is not on main" refused

deploy_with "$(printf 'a%.0s' {1..40})" ghcr.io/dimazak2704/rozklad
check "refuses a sha that does not exist" refused

deploy_with "$main_sha" ghcr.io/dimazak2704/rozklad other.example.org
check "refuses a DOMAIN other than the server's" refused

# a variable of the ssh session must not replace the pinned value
printf '%s\n' "$main_sha" ghcr.io/evil/rozklad deploy-bot ghs_token rozklad.example.org >"$sb/log/stdin"
rm -f "$sb/log/deploy" "$sb/log/docker"
DEPLOY_IMAGE_PREFIX=ghcr.io/evil/rozklad STDIN_FILE="$sb/log/stdin" run_script "$sb" ci-entry.sh
detail="exit $status; stderr: $(cat "$sb/log/err")"
check "reads the pinned prefix from .env, not from the environment" refused
rm -rf "$sb"

# ---- prefix derived from the GitHub owner of origin ---------------------------------------------------
setup
env_file DOMAIN=rozklad.example.org

origin_url https://github.com/DimaZak2704/rozklad-vtfk.git
deploy_with "$main_sha" ghcr.io/dimazak2704/rozklad
check "derives ghcr.io/<owner lower-case>/rozklad from an https origin" deployed ghcr.io/dimazak2704/rozklad "$main_sha"

deploy_with "$main_sha" ghcr.io/evil/rozklad
check "refuses another owner when the prefix is derived" refused

origin_url git@github.com:DimaZak2704/rozklad-vtfk.git
deploy_with "$main_sha" ghcr.io/dimazak2704/rozklad
check "derives the prefix from an ssh origin too" deployed ghcr.io/dimazak2704/rozklad "$main_sha"

g config --unset-all "url.$(cygpath -m "$sb/origin.git" 2>/dev/null || printf '%s' "$sb/origin.git").insteadOf"
g config remote.origin.url "$sb/origin.git"
deploy_with "$main_sha" ghcr.io/dimazak2704/rozklad
check "refuses everything when the owner is unknown and nothing is pinned" refused
check "says to set DEPLOY_IMAGE_PREFIX then" grep -q DEPLOY_IMAGE_PREFIX "$sb/log/err"
rm -rf "$sb"

finish
