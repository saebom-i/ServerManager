#!/bin/bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:8790 -sTCP:LISTEN >/dev/null 2>&1; then echo '테스트 포트 8790이 사용 중입니다.' >&2; exit 1; fi
export SM_DESKTOP_HOME="$(mktemp -d /tmp/servermanager-desktop.XXXXXX)"
SERVER_MANAGER_HOME="$SM_DESKTOP_HOME" SERVER_MANAGER_DATA="$SM_DESKTOP_HOME/profiles" 'build/macos-package/root/Applications/Server Manager.app/Contents/MacOS/ServerManager' --service --server.port=8790 --server-manager.public-origin=http://localhost:8790 >"$SM_DESKTOP_HOME/bootstrap.log" 2>&1 &
bootstrap_pid=$!
cleanup(){ kill -TERM "$bootstrap_pid" 2>/dev/null || true; wait "$bootstrap_pid" 2>/dev/null || true; }
trap cleanup EXIT
ready=false
for attempt in $(seq 1 60); do
 if curl -fsS http://localhost:8790/api/auth/session >/dev/null 2>&1; then ready=true; break; fi
 if ! kill -0 "$bootstrap_pid" 2>/dev/null; then cat "$SM_DESKTOP_HOME/bootstrap.log"; exit 1; fi
 sleep 1
done
if [ "$ready" != true ]; then echo '설치 앱이 시작되지 않았습니다.' >&2; exit 1; fi
(cd frontend && npx playwright test --config playwright.desktop.config.ts)
