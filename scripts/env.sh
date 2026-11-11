# Source this file in Git Bash before running Maven:  source scripts/env.sh
# The build needs JDK 25. ROZKLAD_JAVA_HOME wins; otherwise the newest JDK 25 under ~/.jdks is used,
# and if there is none, JAVA_HOME stays as it is.
if [ -n "${ROZKLAD_JAVA_HOME:-}" ]; then
  export JAVA_HOME="$ROZKLAD_JAVA_HOME"
else
  # no ls: callers run with set -euo pipefail, and a glob that matches nothing must not fail
  _rozklad_jdk=""
  for _rozklad_dir in "$HOME"/.jdks/jdk-25* "$HOME"/.jdks/temurin-25*; do
    if [ -d "$_rozklad_dir" ]; then _rozklad_jdk="$_rozklad_dir"; fi
  done
  if [ -n "$_rozklad_jdk" ]; then export JAVA_HOME="$_rozklad_jdk"; fi
  unset _rozklad_jdk _rozklad_dir
fi
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
# Maven 3.9+: ROZKLAD_MAVEN_BIN, or the one already on PATH
if [ -n "${ROZKLAD_MAVEN_BIN:-}" ]; then export PATH="$ROZKLAD_MAVEN_BIN:$PATH"; fi
if ! command -v mvn >/dev/null 2>&1; then echo "env.sh: mvn is not on PATH (set ROZKLAD_MAVEN_BIN)" >&2; fi
export MAVEN_OPTS="-Xmx640m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
export PYTHONIOENCODING=utf-8
