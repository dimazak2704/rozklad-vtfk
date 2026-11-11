# Source this file in Git Bash before running Maven:  source scripts/env.sh
# The build needs JDK 25. ROZKLAD_JAVA_HOME wins; otherwise the newest JDK 25 under ~/.jdks is used,
# and if there is none, JAVA_HOME stays as it is.
if [ -n "${ROZKLAD_JAVA_HOME:-}" ]; then
  export JAVA_HOME="$ROZKLAD_JAVA_HOME"
else
  _rozklad_jdk=$(ls -d "$HOME"/.jdks/jdk-25* "$HOME"/.jdks/temurin-25* 2>/dev/null | sort -V | tail -1)
  [ -n "$_rozklad_jdk" ] && export JAVA_HOME="$_rozklad_jdk"
  unset _rozklad_jdk
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
# Maven 3.9+: ROZKLAD_MAVEN_BIN, or the one already on PATH
[ -n "${ROZKLAD_MAVEN_BIN:-}" ] && export PATH="$ROZKLAD_MAVEN_BIN:$PATH"
command -v mvn >/dev/null 2>&1 || echo "env.sh: mvn is not on PATH (set ROZKLAD_MAVEN_BIN)" >&2
export MAVEN_OPTS="-Xmx640m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
export PYTHONIOENCODING=utf-8
