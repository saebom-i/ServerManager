#!/bin/bash
set -euo pipefail
package_home="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -m)" in arm64) arch=aarch64;; x86_64) arch=x64;; *) echo "지원하지 않는 CPU" >&2;exit 1;; esac
read -r -p "관리 웹 HTTPS 주소 (예: https://mc.example.com): " manager_url
exec "$package_home/runtimes/$arch/Contents/Home/bin/java" -jar "$package_home/server-manager.jar" --runner-pair "--manager-url=$manager_url"
