#!/bin/bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:8793 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "테스트 포트 8793가 사용 중입니다. 해당 서비스를 확인하세요." >&2
  exit 1
fi
export SM_LOG_TEST="$(mktemp -d /tmp/server-manager-logs.XXXXXX)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
SERVER_MANAGER_DATA="$SM_LOG_TEST" "$JAVA_HOME/bin/java" -Duser.home="$SM_LOG_TEST/home" -jar "${SM_TEST_JAR:-build/libs/server-manager-$(cat VERSION).jar}" --server.address=127.0.0.1 --server.port=8793 --server-manager.public-origin=http://localhost:8793 >"$SM_LOG_TEST/app.log" 2>&1 &
test_pid=$!
cleanup() { kill -TERM "$test_pid" 2>/dev/null || true; wait "$test_pid" 2>/dev/null || true; }
trap cleanup EXIT
ready=false
for attempt in $(seq 1 60); do
  if curl -fsS http://localhost:8793/api/auth/session >/dev/null; then ready=true; break; fi
  if ! kill -0 "$test_pid" 2>/dev/null; then cat "$SM_LOG_TEST/app.log"; exit 1; fi
  sleep 1
done
if [ "$ready" != true ]; then echo "테스트 앱 시작 실패: $SM_LOG_TEST/app.log" >&2; exit 1; fi
echo "격리된 테스트 데이터: $SM_LOG_TEST"
(cd frontend && npx playwright test --config playwright.logs.config.ts)
