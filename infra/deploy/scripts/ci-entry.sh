#!/usr/bin/env bash
# What the GitHub deploy job runs on the VPS (.github/workflows/deploy.yml). The job connects over
# SSH and sends five lines on stdin, nothing on the command line:
#   commit sha, image prefix, GHCR user, GHCR token (the job's own, valid while it runs), DOMAIN
# It checks them, logs in to GHCR, runs deploy.sh --pull-images and logs out again.
#
# Make it the forced command of the GitHub key, so a leaked key can do nothing else
# (~/.ssh/authorized_keys on the server, one line; adjust the path to your clone):
#   restrict,command="bash /home/ubuntu/rozklad/infra/deploy/scripts/ci-entry.sh" ssh-ed25519 AAAA... github-deploy
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

# everything runs from main(), so bash has read the whole file before deploy.sh pulls a new version
main() {
  require_server
  local tag prefix user token domain expected
  IFS= read -r tag || true
  IFS= read -r prefix || true
  IFS= read -r user || true
  IFS= read -r token || true
  IFS= read -r domain || true

  [[ $tag =~ ^[0-9a-f]{40}$ ]] || die "the image tag must be a full commit sha, got '$tag'"
  [[ $prefix =~ ^ghcr\.io/[a-z0-9]([a-z0-9-]*[a-z0-9])?/rozklad$ ]] ||
    die "the image prefix must be ghcr.io/<owner>/rozklad, got '$prefix'"
  [[ $user =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\[bot\])?$ ]] || die "unexpected GitHub user name '$user'"
  [[ -n $token && $token != *[[:space:]]* ]] || die "no GHCR token on stdin"

  # The web image has the OIDC authority of the repository variable DOMAIN baked in; Keycloak and
  # the services take theirs from DOMAIN in .env. Different values break every login.
  expected=$(env_get DOMAIN)
  [[ $domain == "$expected" ]] ||
    die "the repository variable DOMAIN is '$domain', but DOMAIN in $ROZKLAD_ENV_FILE is '$expected'.
       Make them equal, then run the Deploy workflow again."

  printf '%s' "$token" | docker login ghcr.io -u "$user" --password-stdin >/dev/null
  trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
  IMAGE_PREFIX=$prefix IMAGE_TAG=$tag bash "$here/deploy.sh" --pull-images
}

main "$@"
