#!/bin/bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
if lsof -nP -iTCP:8796 -sTCP:LISTEN >/dev/null 2>&1; then echo 'Test port 8796 is busy.' >&2; exit 1; fi
export SM_RUNNERS_TEST_HOME="$(mktemp -d /tmp/servermanager-runners.XXXXXX)"
export SM_RUNNERS_TEST_JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
export SM_RUNNERS_TEST_JAR="$PWD/build/libs/server-manager-$(cat VERSION).jar"
test_keytool="${SM_RUNNERS_TEST_JAVA%/java}/keytool"
# Disposable local certificate and password; never use the production key or config.
"$test_keytool" -genkeypair -alias fixture -keyalg RSA -storetype PKCS12 -keystore "$SM_RUNNERS_TEST_HOME/tls.p12" -storepass fixture-only-password -keypass fixture-only-password -dname CN=localhost -ext SAN=dns:localhost -validity 2 >/dev/null 2>&1
cat >"$SM_RUNNERS_TEST_HOME/test.properties" <<EOF
spring.config.import=
server.address=127.0.0.1
server.port=8796
server-manager.public-origin=https://localhost:8796
server.ssl.enabled=true
server.ssl.key-store=file:$SM_RUNNERS_TEST_HOME/tls.p12
server.ssl.key-store-password=fixture-only-password
server.ssl.key-store-type=PKCS12
server.servlet.session.cookie.name=SMSESSION
server.servlet.session.cookie.same-site=strict
spring.task.scheduling.pool.size=3
logging.level.local.servermanager.RunnerGateway=DEBUG
EOF
SERVER_MANAGER_DATA="$SM_RUNNERS_TEST_HOME/profiles" SERVER_MANAGER_HOME="$SM_RUNNERS_TEST_HOME/manager" "$SM_RUNNERS_TEST_JAVA" -jar "$SM_RUNNERS_TEST_JAR" --spring.config.location="file:$SM_RUNNERS_TEST_HOME/test.properties" >"$SM_RUNNERS_TEST_HOME/manager.log" 2>&1 &
manager_pid=$!
cleanup(){ kill -TERM "$manager_pid" 2>/dev/null || true; wait "$manager_pid" 2>/dev/null || true; }
trap cleanup EXIT
ready=false
for attempt in $(seq 1 60); do
 if curl -kfsS https://localhost:8796/api/auth/session >/dev/null 2>&1; then ready=true; break; fi
 if ! kill -0 "$manager_pid" 2>/dev/null; then tail -50 "$SM_RUNNERS_TEST_HOME/manager.log"; exit 1; fi
 sleep 1
done
if [ "$ready" != true ]; then echo 'Isolated manager did not start.' >&2; exit 1; fi
(cd frontend && npx playwright test --config playwright.runners-live.config.ts)
echo "Live runner fixture results: $SM_RUNNERS_TEST_HOME (no production profile used)"
