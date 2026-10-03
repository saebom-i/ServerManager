#!/bin/bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:8789 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "테스트 포트 8789가 사용 중입니다. 해당 서비스를 확인하세요." >&2
  exit 1
fi
export SM_E2E_DATA="$(mktemp -d /tmp/server-manager-security.XXXXXX)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
SERVER_MANAGER_DATA="$SM_E2E_DATA" "$JAVA_HOME/bin/java" -Duser.home="$SM_E2E_DATA/home" -jar build/libs/server-manager-$(cat VERSION).jar --server.address=127.0.0.1 --server.port=8789 --server-manager.public-origin=http://localhost:8789 >"$SM_E2E_DATA/app.log" 2>&1 &
test_pid=$!
cleanup() { kill -TERM "$test_pid" 2>/dev/null || true; wait "$test_pid" 2>/dev/null || true; }
trap cleanup EXIT
ready=false
for attempt in $(seq 1 60); do
  if curl -fsS http://localhost:8789/api/auth/session >/dev/null; then ready=true; break; fi
  if ! kill -0 "$test_pid" 2>/dev/null; then cat "$SM_E2E_DATA/app.log"; exit 1; fi
  sleep 1
done
if [ "$ready" != true ]; then echo "테스트 앱 시작 실패: $SM_E2E_DATA/app.log" >&2; exit 1; fi
echo "격리된 테스트 데이터: $SM_E2E_DATA"
(cd frontend && npx playwright test --config playwright.security.config.ts)
