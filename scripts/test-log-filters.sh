#!/bin/bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:8797 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Test port8797 is busy.' >&2; exit 1; fi
export SM_FILTERS_HOME="$(mktemp -d /tmp/servermanager-filters.XXXXXX)"
filter_test_java="$(/usr/libexec/java_home -v 21)/bin/java"
SERVER_MANAGER_DATA="$SM_FILTERS_HOME/profiles" SERVER_MANAGER_HOME="$SM_FILTERS_HOME/manager" "$filter_test_java" -Duser.home="$SM_FILTERS_HOME/home" -jar "build/libs/server-manager-$(cat VERSION).jar" --server.address=127.0.0.1 --server.port=8797 --server-manager.public-origin=http://localhost:8797 >"$SM_FILTERS_HOME/app.log" 2>&1 &
filter_test_pid=$!
cleanup(){ kill -TERM "$filter_test_pid" 2>/dev/null || true; wait "$filter_test_pid" 2>/dev/null || true; }
trap cleanup EXIT
ready=false
for attempt in $(seq 1 60); do
 if curl --noproxy localhost -fsS http://localhost:8797/api/auth/session >/dev/null 2>&1; then ready=true;break;fi
 if ! kill -0 "$filter_test_pid" 2>/dev/null;then echo "Fixture failed: $SM_FILTERS_HOME/app.log" >&2;exit 1;fi
 sleep 1
done
if [ "$ready" != true ];then echo 'Isolated filter fixture startup timed out.' >&2;exit 1;fi
(cd frontend && npx --no-install playwright test -c playwright.filters-live.config.ts)
echo "Real filter authorization/persistence fixture passed: $SM_FILTERS_HOME"
