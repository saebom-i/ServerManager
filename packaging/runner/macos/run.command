#!/bin/bash
set -euo pipefail
package_home="$(cd "$(dirname "$0")" && pwd)"
export RUNNER_PACKAGE_HOME="$package_home"
case "$(uname -m)" in arm64) arch=aarch64;; x86_64) arch=x64;; *) echo "지원하지 않는 CPU" >&2;exit 1;; esac
runner_home="${SERVER_MANAGER_RUNNER_HOME:-$HOME/.servermanager-runner}"
if [ ! -f "$runner_home/identity.json" ] && [ -t 0 ] && [ "${SERVER_MANAGER_SKIP_PAIRING:-0}" != 1 ]; then
  echo "이 컴퓨터를 등록해야 서버를 실행할 수 있습니다."
  /bin/bash "$package_home/pair.command"
fi
exec "$package_home/runtimes/$arch/Contents/Home/bin/java" -jar "$package_home/runner-bootstrap.jar"
