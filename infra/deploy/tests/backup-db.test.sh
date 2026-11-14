#!/usr/bin/env bash
# backup-db.sh: a dump of the whole cluster (password hashes, sign-in sessions) may leave the server
# only encrypted, the off-site copy runs on every backup, and a failed run never pings the dead-man
# switch. docker (pg_dumpall), age, rclone and curl are stubs here.
# shellcheck source-path=SCRIPTDIR disable=SC2016,SC2034,SC2012 # stubs are single-quoted, eval checks use the globals
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=testlib.sh
source "$here/testlib.sh"

recipient="age1$(printf 'q%.0s' {1..58})"
dump_text='-- PostgreSQL database cluster dump complete'

setup() {
  sb=$(new_sandbox)
  cp "$SCRIPTS_DIR/backup-db.sh" "$sb/repo/infra/deploy/scripts/"
  mkdir -p "$sb/backups"
  stub "$sb" docker '
printf "%s\n" "$*" >>"$STUB_LOG/docker"
if [[ $* == *pg_dumpall* ]]; then
  [[ -z ${STUB_DUMP_FAIL:-} ]] || exit 1
  printf "%s\n" "-- PostgreSQL database cluster dump complete"
fi'
  stub "$sb" age '
printf "%s\n" "$*" >>"$STUB_LOG/age"
[[ -z ${STUB_AGE_FAIL:-} ]] || exit 1
printf "age-encryption.org/v1\n-> X25519 stub\n---\n"
base64'
  stub "$sb" rclone 'printf "%s\n" "$*" >>"$STUB_LOG/rclone"; [[ -z ${STUB_RCLONE_FAIL:-} ]]'
  stub "$sb" curl 'printf "%s\n" "$*" >>"$STUB_LOG/curl"'
}

env_file() { printf '%s\n' "BACKUP_DIR=$sb/backups" BACKUP_KEEP_DAYS=14 "$@" >"$sb/repo/infra/deploy/.env"; }

backup() {
  run_script "$sb" backup-db.sh
  detail="exit $status; stderr: $(cat "$sb/log/err"); files: $(ls -A "$sb/backups" | tr '\n' ' ')"
}

files() { find "$sb/backups" -maxdepth 1 -type f -name "$1" | wc -l | tr -d ' '; }
called() { [[ -s $sb/log/$1 ]]; }
not_called() { [[ ! -s $sb/log/$1 ]]; }

# ---- without encryption (as before) ---------------------------------------------------------------
setup
env_file
backup
check "plain backup: succeeds" test "$status" = 0
check "plain backup: one valid .sql.gz with the dump" \
  eval '[[ $(files "rozklad-*.sql.gz") == 1 ]] && gzip -dc "$sb"/backups/rozklad-*.sql.gz | grep -qx -- "$dump_text"'
check "plain backup: nothing is sent anywhere" eval 'not_called rclone && not_called curl'
rm -rf "$sb"

# ---- encrypted on the server ----------------------------------------------------------------------
setup
env_file "BACKUP_AGE_RECIPIENT=$recipient"
backup
check "encrypted: succeeds" test "$status" = 0
check "encrypted: one .sql.gz.age and no plain dump" eval '[[ $(files "rozklad-*.sql.gz.age") == 1 && $(files "rozklad-*.sql.gz") == 0 ]]'
check "encrypted: the file is age output" eval 'head -n 1 "$sb"/backups/rozklad-*.sql.gz.age 2>/dev/null | grep -qx "age-encryption.org/v1"'
check "encrypted: age gets the recipient (public key only)" grep -sqx -- "-r $recipient" "$sb/log/age"
check "encrypted: no partial file is left" test "$(files '*.partial')" = 0

# old encrypted dumps are removed after BACKUP_KEEP_DAYS like plain ones
touch -d '30 days ago' "$sb/backups/rozklad-20200101-031500.sql.gz.age" "$sb/backups/rozklad-20200101-031500.sql.gz"
backup
check "retention: old dumps of both kinds are removed" \
  eval '[[ ! -e $sb/backups/rozklad-20200101-031500.sql.gz.age && ! -e $sb/backups/rozklad-20200101-031500.sql.gz ]]'
rm -rf "$sb"

setup
env_file "BACKUP_AGE_RECIPIENT=not-a-key"
backup
check "a recipient that is not an age public key is refused before dumping" \
  eval '[[ $status != 0 ]] && not_called docker && [[ -z $(ls -A "$sb/backups") ]]'
rm -rf "$sb"

# ---- off-site copy ---------------------------------------------------------------------------------
setup
env_file "BACKUP_RCLONE_REMOTE=offsite:rozklad"
backup
check "off-site without encryption is refused: plain dumps never leave the server" \
  eval '[[ $status != 0 ]] && not_called rclone && not_called docker'
rm -rf "$sb"

setup
env_file "BACKUP_AGE_RECIPIENT=$recipient" "BACKUP_RCLONE_REMOTE=offsite:rozklad" "BACKUP_PING_URL=https://ping.example.org/abc"
backup
check "off-site: succeeds" test "$status" = 0
check "off-site: the encrypted file is copied to the remote" \
  eval 'grep -sEq "^copy .*/rozklad-[0-9]{8}-[0-9]{6}\.sql\.gz\.age offsite:rozklad( |$)" "$sb/log/rclone"'
check "off-site: the dead-man switch is pinged after it" grep -sq 'https://ping.example.org/abc' "$sb/log/curl"
rm -rf "$sb"

setup
env_file "BACKUP_AGE_RECIPIENT=$recipient" "BACKUP_RCLONE_REMOTE=offsite:rozklad" "BACKUP_PING_URL=https://ping.example.org/abc"
STUB_RCLONE_FAIL=1 backup
check "off-site failure: the run fails" test "$status" != 0
check "off-site failure: no ping, so the dead-man switch alerts" not_called curl
check "off-site failure: the local encrypted dump is kept" test "$(files 'rozklad-*.sql.gz.age')" = 1
rm -rf "$sb"

# ---- a failed dump -----------------------------------------------------------------------------------
setup
env_file "BACKUP_AGE_RECIPIENT=$recipient" "BACKUP_RCLONE_REMOTE=offsite:rozklad" "BACKUP_PING_URL=https://ping.example.org/abc"
STUB_DUMP_FAIL=1 backup
check "dump failure: the run fails, leaves no file, copies and pings nothing" \
  eval '[[ $status != 0 && -z $(ls -A "$sb/backups") ]] && not_called rclone && not_called curl'
rm -rf "$sb"

# ---- restore of an encrypted dump -------------------------------------------------------------------
setup
env_file
cp "$SCRIPTS_DIR/restore-db.sh" "$sb/repo/infra/deploy/scripts/"
printf 'age-encryption.org/v1
' >"$sb/backups/rozklad-20261107-031500.sql.gz.age"
run_script "$sb" restore-db.sh "$sb/backups/rozklad-20261107-031500.sql.gz.age" --yes
detail="exit $status; stderr: $(cat "$sb/log/err")"
check "restore of an .age file stops before touching anything and says how to decrypt"   eval '[[ $status != 0 ]] && not_called docker && grep -q "age -d" "$sb/log/err"'
rm -rf "$sb"

finish
