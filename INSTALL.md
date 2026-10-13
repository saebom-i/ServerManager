# macOS 설치와 업데이트

[GitHub Releases](https://github.com/yoonseo6399/ServerManager/releases/latest)에서 `ServerManager-0.3.0-universal.pkg`를 내려받아 설치하세요. Intel·Apple Silicon Java 21을 함께 포함하므로 Java·Node·Docker를 별도로 준비할 필요가 없습니다.

현재 개인 개발 빌드는 Apple Developer ID 서명·공증이 없습니다. macOS가 차단하면 시스템 설정 → 개인정보 보호 및 보안에서 해당 설치 파일만 허용하세요. 앱의 업데이트 파일에는 별도의 Ed25519 서명을 적용합니다. 이 서명은 Apple 공증을 대신하지 않습니다.

## 프로필 저장 위치 선택

GUI 설치가 끝나면 로그인한 사용자에게 폴더 선택 창을 엽니다. 기본 위치 또는 다른 폴더를 선택하세요. 설치 중 창을 열 수 없는 환경에서는 앱 첫 실행 시 선택합니다. 빈 폴더 또는 기존 Server Manager 데이터 폴더를 사용할 수 있습니다. 일반 문서가 섞인 폴더는 거절합니다.

프로필 폴더에는 서버 설정, 월드, 모드, 백업, 계정 정보가 포함됩니다. 예전 소스 버전의 `.local-data`를 선택하면 기존 데이터를 사용할 수 있습니다. 먼저 기존 관리 앱과 모든 게임 서버를 종료하세요. 폴더를 선택해도 파일을 옮기거나 삭제하지 않습니다.

기존 소스 버전의 HTTPS 주소를 유지하려면 프로젝트의 `config/application.properties`도 설치 앱의 `~/Library/Application Support/ServerManager/config/application.properties`로 복사하세요. 프로필 폴더와 관리 웹 주소 설정은 별도이며, 패스키는 원래 도메인에서 사용해야 합니다.

설치 후에는 **계정과 보안 → 프로필 저장 위치**에서 절대 경로를 입력해 전환할 수 있습니다. 모든 서버와 설치 작업이 정지돼 있어야 하며, 전환 후 관리 앱을 재시작합니다. 선택한 폴더의 계정으로 로그인하세요. 빈 폴더라면 새로운 소유자 등록이 필요합니다.

설치 앱을 실행하지 않은 상태에서 폴더 선택 창을 다시 열려면:

```bash
"/Applications/Server Manager.app/Contents/MacOS/ServerManager" --configure
```

## 최초 등록·설정 위치

첫 실행 시 관리 웹과 소유자 등록 코드 파일을 엽니다. 코드를 입력하고 패스키를 등록하세요. 코드 유효 시간은 30분입니다.

- 기본 프로필: `~/Library/Application Support/ServerManager/data`
- 선택한 프로필 경로: `~/Library/Application Support/ServerManager/data-location.txt`
- HTTPS 주소·관리 포트: `~/Library/Application Support/ServerManager/config/application.properties`
- 앱 로그: `~/Library/Application Support/ServerManager/logs/app.log`

프로필 폴더를 바꿔도 관리 주소 설정은 유지합니다. 공개 URL은 기본값이 비어 있습니다. nginx·인증서·포트포워딩은 사용자가 구성하며 [SECURITY.md](SECURITY.md)를 참고하세요.

앱과 모든 게임 서버를 종료한 뒤 소유자를 복구하려면 아래 명령을 사용합니다. 기존 소유자 키와 세션을 폐기하므로 키 분실 또는 등록 코드 만료 시에만 사용하세요.

```bash
"/Applications/Server Manager.app/Contents/MacOS/ServerManager" --recover-owner
```

## 자동 시작

**계정과 보안 → Mac 로그인 시 서버 관리자 자동 실행**을 켜거나 끕니다. 다음 로그인부터 적용됩니다. 게임 서버는 자동으로 시작하지 않습니다. 로그인 전 시스템 데몬은 설치하지 않습니다.

로그인 항목은 `~/Library/LaunchAgents/local.servermanager.plist`에 저장됩니다. 앱 삭제 전에는 자동 시작을 끄세요.

## 자동 업데이트

0.3.0 이상 설치 앱에서 기본 활성화됩니다. 앱 시작 1분 후, 이후 6시간마다 GitHub의 최신 정식 릴리스를 확인합니다. 계정과 보안에서 끄거나 지금 확인할 수 있습니다.

1. 앱에 내장된 공개 키로 릴리스 메타데이터의 Ed25519 서명을 검증합니다.
2. JAR 다운로드 크기와 SHA-256을 검증한 뒤 사용자 데이터 영역에 준비합니다.
3. 모든 게임 서버와 설치 작업이 정지되면 새 변경 요청을 차단하고 관리 앱만 재시작합니다. 프로필·계정 파일은 변경하지 않습니다.
4. 실행기는 서명을 다시 검증하고 새 버전의 시작 완료를 확인합니다. 시작 실패 시 이전 버전으로 돌아가며 실패한 동일 버전은 다시 자동 적용하지 않습니다.

현재 자동 업데이트 대상은 웹 UI와 관리 서버 JAR입니다. 번들 Java나 실행기 프로토콜을 변경하는 릴리스는 새 `.pkg` 설치가 필요합니다. 0.2.x 사용자는 0.3.0 `.pkg`를 한 번 설치해야 자동 업데이트를 사용할 수 있습니다.

준비된 업데이트는 `~/Library/Application Support/ServerManager/updates`에 저장합니다. 실패 사유는 `update-error.txt`, 앱 시작 오류는 `logs/app.log`에서 확인합니다. 재시작 후 패스키로 다시 로그인해야 합니다.

## 개발·릴리스

`VERSION`을 올리고 프런트엔드와 JAR를 빌드한 뒤 `python3 scripts/package-macos.py`로 설치본을 생성합니다. `python3 scripts/prepare-release.py`는 로컬 전용 `.release-keys/private.der`로 업데이트를 서명합니다. 개인키는 저장소·설치본에 포함하지 않습니다. 별도로 안전하게 백업하세요.

GitHub Actions 워크플로는 `v<버전>` 태그를 올리면 빌드·테스트·패키징합니다. 자동 서명에는 저장소의 `UPDATE_SIGNING_KEY` 시크릿에 PKCS#8 DER 개인키의 base64 값을 등록해야 합니다. 이 시크릿은 명시적으로 허용한 경우에만 설정합니다. 개인키가 없는 기여자는 테스트·빌드할 수 있지만 신뢰되는 업데이트를 발행할 수 없습니다.

Java 배포는 [공식 Adoptium API](https://github.com/adoptium/api.adoptium.net/blob/main/docs/cookbook.adoc), 릴리스 조회는 [GitHub Releases API](https://docs.github.com/en/rest/releases)를 사용합니다.

## 별도 Mac·Windows 실행기 (BETA)

친구가 서버를 실행할 컴퓨터에 설치하는 프로그램입니다. 관리 웹 설치용 `.pkg`와 별개이며 Java 21을 포함합니다. 관리 웹의 왼쪽 **서버 실행기**에서 플랫폼과 설치 방식을 선택하세요.

| 방식 | Mac Intel·Apple Silicon | Windows x64 |
| --- | --- | --- |
| UI 설치판 (추천) | `ServerManager-Runner-UI-<버전>-mac-universal.zip` | `ServerManager-Runner-UI-<버전>-windows-x64.zip` |
| CLI 설치판 | `ServerManager-Runner-<버전>-mac-universal.zip` | `ServerManager-Runner-<버전>-windows-x64.zip` |

**UI 설치:** ZIP 전체를 풀고 Mac은 **Server Manager Runner Installer.app**, Windows는 **Setup.cmd**를 여세요. 관리 웹 HTTPS 주소를 확인하고 **웹에서 패스키로 등록 코드 발급**을 누릅니다. 초대 사용자도 웹에서 자신의 실행기 이름을 입력해 코드를 발급할 수 있습니다. 5분 안에 설치 창에 코드를 붙여넣고 **설치하고 연결**을 누르세요. **로그인 자동 대기**를 선택할 수 있습니다. 등록 실패 시 설치는 보존되므로 새 코드를 받아 **다시 시도**할 수 있습니다.

**CLI 설치:** 압축을 푼 폴더의 터미널에서 Mac은 `./install.command`, Windows는 `install.cmd`를 실행하세요. 관리 주소와 등록 코드를 순서대로 입력합니다. 코드 입력은 실제 터미널에서 숨깁니다.

```sh
./install.command --help
./install.command --manager-url=https://server.example.com --no-autostart
./install.command --install-only --no-autostart
```

Windows도 같은 옵션을 `install.cmd` 뒤에 붙입니다. 자동화 시 `--code-stdin --manager-url=https://...`로 코드를 표준 입력에서만 읽을 수 있습니다. 코드를 명령 인수·스크립트·파일에 저장하지 마세요. `--install-only` 또는 기존 `SERVER_MANAGER_SKIP_PAIRING=1`은 등록·현재 실행을 생략하며 나중에 `pair.command`/`pair.cmd`, `run.command`/`run.cmd`로 진행합니다.

두 방식은 동일한 실행기와 등록 정보를 사용합니다. 설치 위치는 Mac `~/Applications/Server Manager Runner`, Windows `%LOCALAPPDATA%\ServerManagerRunner`이며 등록·게임 데이터는 `~/.servermanager-runner`에 보존합니다. 기본 자동 시작은 **사용자 로그인 시** Mac LaunchAgent 또는 Windows 사용자 작업 스케줄러를 사용하며 로그인 전 시스템 서비스는 설치하지 않습니다. 실행 중에는 재설치를 거절하므로 게임 저장·정상 종료와 실행기 종료를 마친 뒤 설치하세요. 설치기가 실행 중인 게임을 강제 종료하지 않습니다.

개발 빌드는 Apple Developer ID 공증 및 Windows Authenticode 서명이 없습니다. OS·조직 보안 정책에 따라 실행 승인이 필요할 수 있습니다. Ed25519 자동 업데이트 서명은 OS 공증과 별도입니다.

### 기존 마크 서버 폴더를 원래 위치에서 사용

1. 관리 웹에서 기존 서버와 **동일한 종류·Minecraft·로더 버전**으로 서버를 생성합니다. 런처 설치는 생략할 수 있습니다.
2. **서버 실행기 → 기존 마크 서버 폴더 연결하기**에서 해당 서버 ID를 복사합니다.
3. UI 설치 창에서 **기존 마크 서버 폴더를 원래 위치에서 실행**을 체크하고 서버 ID·폴더를 지정합니다. `server.properties`가 있는 서버 루트 폴더를 고르세요. 일반 폴더나 월드 폴더만 선택하면 거절합니다.
4. Vanilla·Paper·Fabric의 JAR 이름이 기본값과 다르면 `paper-1.21.1.jar` 같은 **파일 이름만** 입력합니다. NeoForge·Forge는 비워두고, 웹 버전에 맞는 `libraries/.../unix_args.txt` 또는 `win_args.txt`를 사용합니다. 임의 실행 스크립트나 셸 명령은 실행하지 않습니다.
5. 설치 후 웹에서 서버 사용 허용 → 실행 위치 배정 → 시작합니다. 처음에는 선택한 원래 경로의 파일이 원본입니다. 실행기가 폴더를 다른 곳으로 가져가 실행하지 않습니다.

CLI에서도 같은 기능을 제공합니다. 경로에 공백이 있으면 따옴표로 감싸세요.

```sh
./install.command --server-id=서버UUID --server-path="/Users/friend/Minecraft/My Server" --server-jar=paper.jar
# 이미 설치되어 있다면, 실행기를 종료한 뒤 폴더 연결만 변경:
./install.command --folder-only --server-id=서버UUID --server-path="/Users/friend/Minecraft/My Server"
```

원래 서버가 실행 중이거나 실행기의 이전 파일 인계가 끝나지 않았으면 등록·시작을 거절합니다. 링크와 서버 폴더 밖의 월드 경로도 거절합니다. `server.properties`·EULA·기존 런타임 메타데이터는 실행기 프로필의 `folder-settings-backups`에 보관합니다. 실행 시 웹에서 설정한 포트와 로컬 수신·Query 설정이 적용됩니다. 첫 종료 후 변경 파일을 관리 서버에 인계하고 다른 실행기로 이동할 수 있습니다. 사용자 지정 JAR 이름과 Java 요구사항도 함께 전달됩니다.

이후 시작은 관리 서버의 최신 파일을 원래 경로에 반영하며, 반영 전 폴더를 같은 디스크의 `.servermanager-backup-...`에 보관합니다. 외부에서 원래 폴더를 수정했다면 덮어쓰기를 중단합니다. 그 변경을 원본으로 사용할 때만 실행기를 종료하고 폴더를 다시 등록하세요. 기존 폴더를 별도 프로그램으로 동시에 실행하지 마세요. 백업은 자동 삭제하지 않으므로 용량을 확인하세요. 번들 Java는 21이며 Java 25가 필요한 서버는 시작 전에 요구사항 오류를 안내합니다.

기기 등록자는 접근 가능한 서버별로 기기 사용을 허용합니다. 그 뒤 해당 서버의 ADMIN 또는 소유자가 **서버 화면 → 실행 위치**에서 배정합니다. 등록된 실행기가 없어도 실행 위치와 등록 안내가 표시됩니다. 서버가 완전히 종료돼야 실행기를 바꿀 수 있습니다. VIEWER에게 배정·임의 콘솔 권한은 주어지지 않습니다. 연결 상태는 서버 실행기 목록에서 10초 간격으로 갱신됩니다.

원격 Minecraft는 실행기 컴퓨터의 127.0.0.1 내부 게임 포트에서 수신합니다. 중앙 Mac은 공개 게임 TCP를 실행기의 WSS 연결로 전달하므로 원격 기기의 포트포워딩은 필요하지 않습니다. 공유 중계가 꺼져 있으면 서버별 중앙 포트가 필요합니다. 여러 컴퓨터의 서버를 공개 포트 하나로 제공하려면 아래 공유 중계를 켜세요. 웹 HTTPS/nginx와 게임 TCP는 별개입니다. 여러 기기의 내부 게임 포트는 같아도 됩니다. 같은 컴퓨터의 여러 서버 동시 실행 확장은 후속 이슈 #1 범위입니다.

원격 서버가 정상 종료되면 변경된 파일만 청크로 관리 Mac에 인계하고 해시를 검증합니다. 데이터 인계 및 선택한 WebDAV 동기화가 완료되기 전에는 다른 실행기에서 시작할 수 없습니다. 이전 실행의 프로세스가 살아 있거나 인계 실패 상태라면 안전하게 상태 확인을 요구합니다. 실행기는 같은 서명된 BETA/정식 채널의 JAR 업데이트를 받아 서버와 파일 인계가 끝난 뒤 적용합니다.

이 기능은 BETA 테스트 대상입니다. 공개 HTTPS/WSS, 대용량 월드 인계, 실제 Nextcloud 및 장애 복구는 사용자가 직접 시험하기 전까지 운영 서버에 바로 적용하지 마세요.


## 서버별 게임 주소와 공유 TCP 중계 (beta.5)

소유자가 왼쪽 **게임 주소·중계**에서 공유 중계를 켜고 공개 포트(예: `6399`), 기본 도메인(예: `server.example.com`), 중앙 Mac으로 연결되는 DNS only 호스트를 입력합니다. 모든 서버·파일 인계·설치 작업이 완전히 정지돼야 설정을 바꿀 수 있습니다. 공유 모드에서는 별도 실행기를 등록하고 배정해야 서버를 시작할 수 있습니다. 중앙 Mac 자체도 별도 실행기로 등록할 수 있으며 이 경우 내부 게임 포트는 공개 중계 포트와 달라야 합니다.

각 서버의 **서버 설정 → 서버 접속 도메인**에서 이름을 지정합니다. `alpha`라면 플레이어 주소는 `alpha.server.example.com`이고 SRV 이름은 `_minecraft._tcp.alpha.server.example.com`입니다. 비우면 `s-<서버ID 앞8자리>`를 사용합니다. 주소는 중복할 수 없고 서버가 종료된 상태에서만 변경됩니다.

- Cloudflare 자동 등록을 켜려면 소유자가 Zone ID와 해당 Zone의 DNS 편집 권한을 가진 API 토큰을 입력합니다. 토큰은 응답에 반환하지 않고 서버의 비공개 디렉터리에 암호화해 저장합니다. 토큰 없이도 실행기와 중계가 작동하지만 DNS는 직접 등록해야 합니다.
- 서버마다 DNS only **A** 레코드를 중앙의 IPv4로 만들고 **SRV**의 포트를 공유 포트, 대상을 그 서버의 호스트 이름으로 지정합니다. SRV 대상도 서버별로 다르므로 클라이언트가 SRV 대상을 핸드셰이크에 넣어도 대상이 구분됩니다.
- Cloudflare 주황 구름 HTTP 프록시로 게임 TCP를 전달하지 않습니다. 관리 웹의 기존 프록시·HTTPS 설정은 보존합니다. 앱이 소유한 DNS만 수정·정리하며 기존 사용자 레코드와 충돌하면 덮어쓰지 않고 오류를 표시합니다.
- 공유기에서 **외부 TCP 6399 → 중앙 Mac TCP 6399**를 한 번 포워딩하면 여러 서버 주소가 그 포트를 공유합니다. 실제 공개 포트에 맞춰 변경하세요. 실행기 쪽 포워딩은 필요하지 않습니다.
- 중앙은 Minecraft의 첫 핸드셰이크 호스트만 읽어 대상 실행기를 선택하고 원본 바이트를 그대로 전달합니다. Forge/FML 확장도 보존합니다. 설정되지 않은 주소는 다른 서버로 자동 연결하지 않습니다.
- 기존 게임 주소와 SRV 대상 호스트를 기본 서버에 연결하려면 **기존 접속 주소 유지**를 설정합니다. 기존 DNS 레코드는 편집하지 않습니다.

서버 종류는 NeoForge·Forge·Fabric·Paper·Vanilla를 선택할 수 있습니다. 공식 조합과 해시를 검증하고 설치 메타데이터를 서버 파일에 보관합니다. 새로운 종류를 원격에서 시작하려면 실행기를 beta.5 이상으로 업데이트하세요. Java 21보다 높은 버전을 요구하는 Minecraft는 설치·시작 전에 Java 버전 오류로 안내합니다.


## 플레이어가 없을 때 절전 (beta.6)

서버별 **서버 설정 → 플레이어가 없을 때 절전**에서 ON/OFF와 30~60분 대기 시간을 설정합니다. 기본값은 OFF·30분입니다. 서버 ADMIN 또는 소유자가 설정하며 VIEWER는 조회만 가능합니다.

서로 다른 최신 인원 조회가 연속으로 0명일 때만 시간을 계산합니다. 접속, 조회 실패·오래된 결과, 실행기 연결 끊김, 서버 재시작·시작 중 및 진단 중에는 타이머가 초기화됩니다. 종료 직전에 실제 실행하는 컴퓨터에서 다시 인원을 확인하고 정상 `stop`으로 월드 저장을 기다립니다. 자동 강제 종료는 하지 않습니다.

원격 절전에는 beta.6 이상 실행기가 필요합니다. 구버전에는 검증 없는 stop 명령을 대신 보내지 않습니다. 실행기 업데이트도 게임과 파일 인계가 끝난 뒤 적용됩니다. 기존 Discord 권한 보유자는 절전 종료된 서버를 시작 버튼으로 다시 켤 수 있습니다. 웹 상태와 Discord 패널·상태 명령에 절전 대기와 종료 사유를 표시합니다. 대기 타이머는 메모리에 두고 관리 앱이 재시작하면 새로 관찰하며, 최근 종료 사유만 서버 설정 폴더에 보관합니다.
