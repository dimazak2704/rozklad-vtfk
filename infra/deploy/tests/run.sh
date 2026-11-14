#!/usr/bin/env bash
# Tests of the deployment scripts and configuration (CI job "Deployment config"):
#   infra/deploy/tests/run.sh
#
#   *.test.sh          shell scripts with stub commands, no Docker needed
#   *.docker.test.sh   start throwaway containers of images the stack already uses; skipped when
#                      Docker is not reachable (ROZKLAD_TEST_DOCKER=0 skips them too)
#   config_test.py     renders the compose files with `docker compose config` and checks them
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

failed=()
docker_ok=0
if [[ ${ROZKLAD_TEST_DOCKER:-1} != 0 ]] && docker info >/dev/null 2>&1; then docker_ok=1; fi

for test in "$here"/*.test.sh; do
  if [[ $test == *.docker.test.sh && $docker_ok == 0 ]]; then
    printf 'skip  %s (no Docker)\n' "$(basename "$test")"
    continue
  fi
  bash "$test" || failed+=("$(basename "$test")")
done

if ((docker_ok)); then
  python=python3
  "$python" -c '' 2>/dev/null || python=python
  "$python" "$here/config_test.py" || failed+=(config_test.py)
else
  printf 'skip  config_test.py (no Docker)\n'
fi

if ((${#failed[@]})); then
  printf 'failed: %s\n' "${failed[*]}"
  exit 1
fi
printf 'all deployment tests passed\n'
