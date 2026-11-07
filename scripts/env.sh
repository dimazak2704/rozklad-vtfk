# Source this file in Git Bash before running Maven:  source scripts/env.sh
# The default `java` on this machine is Java 8, so JDK 25 is set explicitly.
export JAVA_HOME="/c/Users/Dell/.jdks/jdk-25.0.5+8"
export PATH="$JAVA_HOME/bin:/c/Storage/Programs/apache-maven-3.9.11/bin:$PATH"
export MAVEN_OPTS="-Xmx640m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
export PYTHONIOENCODING=utf-8
