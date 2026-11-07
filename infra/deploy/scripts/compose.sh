#!/usr/bin/env bash
# docker compose for the production stack (base file + compose.prod.yml + infra/deploy/.env),
# from any directory. Examples:
#   infra/deploy/scripts/compose.sh ps
#   infra/deploy/scripts/compose.sh logs -f --tail=200 core
#   infra/deploy/scripts/compose.sh restart notification
#   infra/deploy/scripts/compose.sh exec postgres psql -U postgres -d core
#   infra/deploy/scripts/compose.sh config      (also works on a development PC)
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

[[ "${1:-}" == config ]] || require_server
compose "$@"
