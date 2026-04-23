#!/bin/sh

#
# Gradle wrapper script for Unix
#

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Resolve the location of the script
PRG="$0"
while [ -h "$PRG" ]; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/$link
    fi
done
PRGDIR=$(dirname "$PRG")
APP_HOME=$(cd "$PRGDIR" && pwd)

# Add default JVM options
GRADLE_OPTS="${GRADLE_OPTS} ${DEFAULT_JVM_OPTS}"

# Determine the Java command
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Check if gradle wrapper jar exists, otherwise use system gradle
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
    # Fallback to system gradle
    exec gradle "$@"
fi

exec "$JAVACMD" $GRADLE_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
