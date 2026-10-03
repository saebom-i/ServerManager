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
