#!/bin/sh

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi

if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD=$(command -v java 2>/dev/null)
fi

if [ -z "$JAVA_CMD" ] || [ ! -x "$JAVA_CMD" ]; then
  echo "JAVA_HOME is not set and no executable java was found." >&2
  exit 1
fi

exec "$JAVA_CMD" ${MAVEN_OPTS:-} \
  -Dmaven.multiModuleProjectDirectory="$BASE_DIR" \
  -classpath "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
