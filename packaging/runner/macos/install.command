#!/bin/bash
set -euo pipefail
source_dir="$(cd "$(dirname "$0")" && pwd)"
target="$HOME/Applications/Server Manager Runner"
mkdir -p "$HOME/Applications" "$HOME/Library/LaunchAgents"
if [ "$source_dir" != "$target" ]; then
  mkdir -p "$target"
  ditto "$source_dir" "$target"
fi
chmod +x "$target/run.command" "$target/pair.command"
label=local.servermanager.runner
plist="$HOME/Library/LaunchAgents/$label.plist"
cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>$label</string>
<key>ProgramArguments</key><array><string>/bin/bash</string><string>$target/run.command</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardOutPath</key><string>$HOME/Library/Logs/ServerManagerRunner-launch.log</string>
<key>StandardErrorPath</key><string>$HOME/Library/Logs/ServerManagerRunner-launch.log</string>
</dict></plist>
EOF
launchctl bootout "gui/$(id -u)" "$plist" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$plist"
echo "실행기 설치 완료. 로그인할 때 자동으로 대기합니다."
runner_home="${SERVER_MANAGER_RUNNER_HOME:-$HOME/.servermanager-runner}"
if [ ! -f "$runner_home/identity.json" ] && [ "${SERVER_MANAGER_SKIP_PAIRING:-0}" != 1 ] && [ -t 0 ]; then
  echo "이제 관리 웹 주소와 패스키로 발급받은 등록 코드를 입력하여 컴퓨터를 등록합니다."
  /bin/bash "$target/pair.command" || echo "등록을 마치지 못했습니다. '$target/pair.command'를 실행하면 다시 등록할 수 있습니다."
else
  echo "등록 확인 또는 재등록: '$target/pair.command'"
fi
