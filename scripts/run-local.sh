#!/bin/bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export GRADLE_USER_HOME="$PWD/.gradle-user"
if [ ! -f build/libs/server-manager-$(cat VERSION).jar ]; then
  (cd frontend && npm ci --cache ../.npm-cache && npm run build)
  ./gradlew bootJar
fi
exec "$JAVA_HOME/bin/java" -jar build/libs/server-manager-$(cat VERSION).jar "$@"
