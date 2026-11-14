#!/usr/bin/env bash
# What the GitHub deploy job runs on the VPS (.github/workflows/deploy.yml). The job connects over
# SSH and sends five lines on stdin, nothing on the command line:
#   commit sha, image prefix, GHCR user, GHCR token (the job's own, valid while it runs), DOMAIN
# It checks them, logs in to GHCR, runs deploy.sh --pull-images and logs out again.
#
# Make it the forced command of the GitHub key, so the key can do nothing else
# (~/.ssh/authorized_keys on the server, one line; adjust the path to your clone):
#   restrict,command="bash /home/ubuntu/rozklad/infra/deploy/scripts/ci-entry.sh" ssh-ed25519 AAAA... github-deploy
#
# Whoever holds the key chooses every stdin line, so the server decides what may run: images of its
# own GHCR owner only (DEPLOY_IMAGE_PREFIX in .env, else ghcr.io/<GitHub owner of origin>/rozklad) and
# only the commit at the tip of origin/main. Images of another owner or an older commit are refused.
# A rollback is done on the server by hand (docs/DEPLOY.md, section 11) or by a revert on main.
set -Eeuo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

# The image prefix this server accepts. Read from the files only: the ssh session must not be able to
# change it through the environment.
expected_image_prefix() {
  local prefix url
  prefix=$(env_file_value "$ROZKLAD_ENV_FILE" DEPLOY_IMAGE_PREFIX)
  if [[ -n $prefix ]]; then
    printf '%s' "$prefix"
    return
  fi
  # the raw value: `git remote get-url` would apply url.*.insteadOf
  url=$(git -C "$ROZKLAD_REPO_DIR" config --get remote.origin.url || true)
  if [[ $url =~ ^(https://github\.com/|ssh://git@github\.com/|git@github\.com:)([A-Za-z0-9-]+)/ ]]; then
    printf 'ghcr.io/%s/rozklad' "${BASH_REMATCH[2],,}"
  fi
}

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

  local allowed main_sha
  allowed=$(expected_image_prefix)
  [[ -n $allowed ]] ||
    die "this server does not know which images it may run: set DEPLOY_IMAGE_PREFIX=ghcr.io/<owner-lowercase>/rozklad
       in $ROZKLAD_ENV_FILE (origin is not a GitHub repository)"
  [[ $prefix == "$allowed" ]] || die "images of '$prefix' are refused: this server runs '$allowed' only"

  # only the commit main points to now; an older run whose commit is no longer the tip stops here and
  # the run of the newer commit deploys
  git -C "$ROZKLAD_REPO_DIR" fetch --quiet origin +refs/heads/main:refs/remotes/origin/main ||
    die "cannot fetch main from origin to check the commit"
  main_sha=$(git -C "$ROZKLAD_REPO_DIR" rev-parse --verify --quiet 'refs/remotes/origin/main^{commit}') ||
    die "origin has no main branch"
  [[ $tag == "$main_sha" ]] || die "commit $tag is not the tip of main ($main_sha): only the latest main is deployed"

  # The workflow names https://<DOMAIN> as the deployment URL; the server serves DOMAIN of .env.
  # Different values mean the job deploys to a server it does not expect.
  expected=$(env_get DOMAIN)
  [[ $domain == "$expected" ]] ||
    die "the repository variable DOMAIN is '$domain', but DOMAIN in $ROZKLAD_ENV_FILE is '$expected'.
       Make them equal, then run the Deploy workflow again."

  printf '%s' "$token" | docker login ghcr.io -u "$user" --password-stdin >/dev/null
  trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
  IMAGE_PREFIX=$prefix IMAGE_TAG=$tag bash "$here/deploy.sh" --pull-images
}

main "$@"
