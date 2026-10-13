#!/bin/bash
set -euo pipefail
package_home="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -m)" in arm64) arch=aarch64;; x86_64) arch=x64;; *) echo '지원하지 않는 CPU입니다.' >&2; exit 1;; esac
exec "$package_home/runtimes/$arch/Contents/Home/bin/java" -jar "$package_home/runner-setup.jar" --cli "$@"
