# Server Manager

Mac에서 Minecraft 관리 웹을 실행하고, Mac·Windows 실행기에 서버를 배정해 콘솔과 파일을 관리하는 프로그램입니다. NeoForge·Forge·Fabric·Paper·Vanilla를 지원합니다. Kotlin/Spring Boot와 React/TypeScript로 구현했습니다.

패스키 로그인과 외부 접속 설정은 [SECURITY.md](SECURITY.md)를 참고하세요. 공개 URL은 기본적으로 빈 값이며 nginx는 직접 구성합니다.

공개 저장소: [yoonseo6399/ServerManager](https://github.com/yoonseo6399/ServerManager) · [최신 설치 파일](https://github.com/yoonseo6399/ServerManager/releases/latest)

## macOS 설치

`dist/ServerManager-0.3.0-universal.pkg`에 Intel·Apple Silicon Java 21이 포함되어 있습니다. 설치 후 응용 프로그램의 **Server Manager**를 실행하세요. 자동 시작 토글과 데이터 위치는 [INSTALL.md](INSTALL.md)를 참고하세요.

## 소스에서 실행

Java 21, Node.js 20.19+ 또는 22.12+가 필요합니다. 첫 빌드와 서버 소프트웨어 설치에는 인터넷 연결이 필요합니다.

```bash
./scripts/run-local.sh
```

브라우저에서 **http://localhost:8787** 을 여세요. 이미 빌드된 JAR가 있으면 Node.js나 Gradle 없이 실행합니다.

```bash
"$(/usr/libexec/java_home -v 21)/bin/java" -jar build/libs/server-manager-0.3.0.jar
```

관리 포트는 `config/application.properties`의 `server.port`로 변경합니다. 데이터 위치는 환경변수로 지정합니다. 명령행 옵션은 설정 파일보다 우선합니다.

```bash
SERVER_MANAGER_DATA=/absolute/path/to/data ./scripts/run-local.sh --server.port=8788
```

Java를 명시하려면 `JAVA_HOME`을 지정하세요. Minecraft 프로세스의 Java 경로는 서버별 UI에서 설정할 수 있습니다.

## 사용 순서

1. **서버 만들기**에서 서버 종류, Minecraft·런처 버전, Java 경로, 내부 게임 포트와 최대 힙 메모리를 선택합니다.
2. 선택한 종류의 **설치** 버튼을 누르고 콘솔의 설치 진행을 확인합니다. 공식 다운로드 해시와 필요한 Java 버전을 검사합니다.
3. 설치 완료 후 실행기를 배정하고 **시작**을 누릅니다. 공유 중계가 꺼져 있을 때는 관리 Mac에서 로컬 실행도 가능합니다. 공유 중계 설정은 [INSTALL.md](INSTALL.md)의 게임 주소 절을 참고하세요.
4. 콘솔에 `list`, `say hello` 등을 입력합니다. 명령과 출력은 별도로 표시됩니다.
5. **종료** 후 파일 관리자에서 설정 파일을 편집하거나 모드 파일을 업로드합니다.

요청자의 명시적 지시에 따라 이 로컬 버전은 새 서버 생성 시 Minecraft EULA에 자동 동의하고 시각을 `eula.txt`에 기록합니다. EULA: https://aka.ms/MinecraftEULA

## 구현된 기능

- NeoForge·Forge·Fabric·Paper·Vanilla 공식 버전 조회·설치, 공식 SHA-256/SHA-1 및 파일 크기 검증
- 초대 사용자 패스키 기반 Mac·Windows 실행기 등록, 서버별 사용 허용·배정과 온라인 상태
- 공유 TCP 게임 포트, Minecraft 핸드셰이크 주소별 중계, 서버별 하위 도메인 및 Cloudflare DNS only A·SRV 자동 등록
- 서로 다른 실행기에서 서버 실행, 정상 종료·재시작, 포트 충돌 검사. 한 컴퓨터의 여러 서버 동시 실행 확장은 [후속 이슈 #1](https://github.com/yoonseo6399/ServerManager/issues/1)
- 실제 물리 메모리에 따른 동적 힙 상한: `물리 메모리 − max(1 GiB, 물리 메모리의 10%)`
- 시작 시 다른 활성 인스턴스의 최대 힙 합산 검사. OS의 현재 여유 메모리는 변동하는 참고값으로 별도 표시
- 입력·출력 구분, 최근 300줄부터 동적 조회, 이전 로그 페이지 로딩, 화면에 보이는 행만 렌더링, 검색·오류 필터·자동 스크롤, 스택 프레임의 메서드·소스 위치 요약, 프레임별 모드/Mixin 설정/클래스 상세
- 콘솔 입력과 로그 렌더링 분리, 화면에 중복된 시간 접두어 제거(원본 보존)
- 초대 대기 사용자 권한 편집, macOS 로그인 시 자동 실행 토글
- 설치 후 프로필 저장 폴더 선택·전환, Ed25519로 검증하는 자동 앱 업데이트
- 폴더 탐색·생성, 파일 업로드·다운로드·편집·이름 변경·이동·휴지통 삭제
- 파일 편집 시 해시 기반 충돌 감지, 덮어쓰기 전 원본 백업
- 인스턴스 설정 지속 저장, 관리 프로그램 종료 시 실행 서버 정상 종료 시도
- 소유자 전용 서버 삭제: 정지 후 서버 이름 확인, 월드·모드·설정을 `deleted-instances/`로 보관

## 데이터

기본 저장 위치는 실행 디렉터리의 `.local-data/`입니다.

```text
.local-data/<instance-id>/
  instance.json       # 이름, 로더, 포트, 메모리, Java 경로
  installed.ok        # 설치 성공 표식
  server/             # 월드, mods, 설정, 선택한 런처 런타임
  console.log         # 원본 프로세스 출력 (실행 간 누적)
  backups/            # 덮어쓰기 전 파일 원본
  trash/              # UI에서 삭제한 파일 및 폴더
```

휴지통과 백업은 현재 로컬 폴더에서 직접 복구합니다. UI 복원, 전체 월드 백업, 로그 순환은 후속 범위입니다. 라이브 로그는 최근 10,000개까지 메모리에 유지하고 원본 출력은 디스크에 보존합니다.

파일 쓰기는 설치·실행·종료 중 차단합니다. 텍스트 편집은 2 MiB 이하, 업로드는 요청당 256 MiB까지 지원합니다. 다음 시작 시 UI의 포트와 로컬 전용 바인딩이 `server.properties`에 반영되므로 해당 값은 **서버 설정 UI**에서 바꾸세요.

## 로그 파일 조회

콘솔 위 **로그 파일** 메뉴에서 실시간 콘솔, 인스턴스의 `console.log`, Minecraft `server/logs/*.log`·`*.log.gz`, `server/crash-reports/*.txt`를 선택합니다. 파일은 300줄씩 이전/다음 페이지로 읽습니다. 검색은 현재 로드된 로그에 적용됩니다. 기록 중인 파일이 바뀌면 **로그 다시 불러오기**로 새로 조회하세요.

스택 프레임은 `Screen.wrapScreenError() · Screen.java:451`처럼 한 줄로 표시하고, 클릭하면 모드 → Mixin 설정 파일 → 클래스 순으로 상세를 확인합니다. 기타 변환 정보와 원문은 접어서 보관합니다. 알 수 없는 형식은 원문으로 표시합니다.

파싱은 브라우저 Web Worker에서 런타임에 수행합니다. 파싱 결과나 조회 인덱스를 디스크에 저장하지 않습니다. 새 로그가 없으면 파싱을 건너뛰며, 실제 파싱이 0.5초를 넘을 때만 로그 영역 위에 로딩 표시를 겹쳐 띄웁니다. 로딩 표시가 화면 배치나 스크롤 위치를 변경하지 않습니다. 페이지 단위로 처리해 브라우저 전체가 멈추는 것을 방지합니다. 압축 로그는 압축 해제 기준 128 MiB, 개별 행은 1 MiB까지 지원합니다. 더 큰 데이터는 원본 파일로 확인하세요. 압축 로그의 뒤쪽 페이지는 처음부터 해당 위치까지 압축을 해제하므로 더 오래 걸릴 수 있습니다.

## 개발과 테스트

```bash
cd frontend
npm ci --cache ../.npm-cache
npm run build
cd ..
JAVA_HOME="$(/usr/libexec/java_home -v 21)" GRADLE_USER_HOME="$PWD/.gradle-user" ./gradlew test bootJar
```

UI 개발은 `frontend`에서 `npm run dev`로 실행합니다. Vite는 `/api` 요청을 로컬 백엔드 8787 포트로 전달합니다. 별도 개발 데이터 폴더의 백엔드는 `--server-manager.public-origin=http://localhost:5173`으로 실행해야 개발 UI에서 인증할 수 있습니다. 운영 데이터의 패스키 도메인은 바꾸지 마세요.

실제 서버·인증 통합 테스트는 빌드 후 아래 명령으로 실행합니다. 임시 데이터 폴더와 8789 포트를 사용하며 패스키·권한·CPU 표시·서버 삭제를 검증합니다. NeoForge를 내려받고 테스트 종료 시 앱을 정상 종료합니다.

```bash
./scripts/test-security.sh
```

기존 `scripts/smoke-test.py`와 `frontend/tests`는 로그인 도입 전의 테스트이며 현재 인증 설정에서 직접 실행하지 않습니다. 브라우저 테스트는 `frontend`에서 `npx playwright install chromium`으로 Chromium을 먼저 설치하세요.

## 이번 단계의 범위

관리 웹과 실행기 내부 Minecraft는 **127.0.0.1** 에 바인딩합니다. 원격 실행기는 관리 웹으로 먼저 WSS 연결을 맺습니다. 공유 게임 중계는 중앙 Mac의 설정한 포트를 공개 수신하며 웹 HTTPS/nginx와 별개입니다. 패스키·초대·권한·감사 기록을 적용했고, HTTPS·nginx·공유기 설정은 사용자가 구성합니다. 설치본과 실행기에는 Java 21이 포함되며, 더 높은 Java 버전이 필요한 Minecraft는 해당 컴퓨터의 런타임을 먼저 준비해야 합니다. mrpack은 NeoForge·Forge·Fabric 팩의 검사·해시 확인·서버 overrides 적용·로더 설치를 지원합니다. Mixin 목록은 해당 클래스에 연결된 정보이며, 특정 메서드 변경이나 오류 원인을 단정하지 않습니다.

현재 여유 물리 메모리는 Java의 실제 네이티브 메모리 사용량이나 다른 앱의 향후 사용량을 보장하지 않습니다. 힙 외 오버헤드에 대한 여유는 위 예산으로 확보하되, 서버가 많으면 힙을 더 낮춰 사용하세요.

정상 종료는 월드 저장을 기다립니다. 강제 종료나 관리 프로세스의 비정상 종료 후에는 남아 있는 Java 프로세스·포트를 확인해야 합니다. 다음 시작의 포트 검사는 중복 실행을 막지만 아직 프로세스 재연결은 지원하지 않습니다.

로그·성능 회귀 검증 (운영 데이터와 별도):

```bash
npm --prefix frontend run build
(cd frontend && npx playwright test --config playwright.performance.config.ts)
# JAR 빌드 후 실제 인증/CSP/로그 API 통합 검증
bash scripts/test-logs.sh
```

## 로컬 수정본 적용 기본 방식

로컬 실행 앱을 교체할 때는 실행 중인 Minecraft 서버를 먼저 정상 종료하고 저장 완료를 확인한 뒤 적용합니다. 종료가 지연되면 강제 종료하지 않고 원인을 확인합니다. 기존 계정 파일이 실제로 존재하고 등록된 소유자 패스키가 있는지 확인하고, 인증 파일과 설정을 백업한 뒤 교체합니다. GitHub 푸시·태그·릴리스 배포는 사용자가 별도로 요청한 경우에만 진행합니다.


### Discord 봇 (0.4.0 BETA)

소유자의 **계정과 보안 → Discord 봇**에서 Bot 토큰을 입력하고 연동을 켭니다. 연결 후 표시되는 초대 링크로 Discord 서버에 봇을 추가하고, Discord 개발자 모드에서 복사한 서버·역할·텍스트 채널 ID로 규칙을 저장하세요. 역할 규칙은 Minecraft 인스턴스별 조회 권한과 선택적인 시작 권한을 부여합니다. 기본은 권한 없음이며 @everyone은 허용하지 않습니다.

- `/mc status server:...`: 허용된 서버 상태 확인. `/mc start server:...`: 시작 권한이 있는 서버 실행. 자동 완성에는 역할에 허용된 서버만 표시됩니다.
- 명령을 받을 때마다 Discord의 현재 멤버 역할을 다시 조회하며, 서버·역할·인스턴스를 함께 검사합니다. 역할 권한이 웹 소유자나 콘솔 권한으로 확대되지 않습니다. 응답은 요청자에게만 표시됩니다.
- 종료 및 오류 알림을 서버별 텍스트 채널로 보냅니다. 정상 종료 요청과 예기치 않은 종료를 구분합니다. 오류는 로그의 ERROR/Exception 감지 알림이며 충돌의 확정 진단이 아닙니다. 반복 오류는 최대 분당 한 묶음으로 전송하고 로그 원문·토큰·로컬 경로는 전송하지 않습니다. 멘션은 비활성화됩니다. 알림 채널을 볼 수 있는 모든 사람은 해당 알림을 볼 수 있습니다.
- Bot 토큰은 관리자 응답에 반환하지 않고 프로필의 `security/discord`에 AES-GCM 암호화하여 저장합니다. 디렉터리는 0700, 설정과 로컬 암호화 키는 0600입니다. 이 보호는 Mac 계정 자체의 침해를 방어하지 못하므로 프로필 백업도 비공개로 보관하세요. 저장된 토큰을 교체·삭제할 수 있습니다.
- Gateway 방식으로 외부에 연결하므로 추가 포트나 nginx 라우팅이 필요 없습니다. Developer Portal의 Interactions Endpoint URL은 비워 두고 특권 인텐트는 켜지 않아도 됩니다. 봇에 채널 보기와 메시지 보내기 권한을 주세요. 전용 봇 사용을 권장합니다(`/mc` 명령을 등록합니다).
- Mac과 관리 앱이 실행되어야 동작합니다. 관리 앱 자체가 중지되거나 Discord 연결이 끊긴 동안에는 명령·알림을 처리하지 못하며 과거 로그를 재전송하지 않습니다. 연결 상태 및 실패 안내는 관리자 화면에 표시됩니다. 토큰 없이도 앱의 다른 기능은 정상 작동합니다.

BETA 설치는 BETA prerelease 업데이트를 확인하고, 정식 설치는 정식 릴리스만 확인합니다. 0.3.0의 런처는 BETA 버전을 이해하지 못하므로 첫 BETA 전환은 새 PKG로 설치하세요. 30~60분 무인 서버 절전, Modrinth 확장 및 모드 이진 탐색은 예약된 다음 개발 범위입니다.


다섯 서버 종류의 실제 설치·시작·월드 저장 검증과 HTTPS/WSS 실행기·공유 TCP 통합 검증은 운영 계정·월드와 다른 임시 프로필에서 수행합니다. 테스트는 인터넷 연결과 Java 21을 사용합니다.

```bash
SM_LIVE_LAUNCHERS=1 ./gradlew test --tests local.servermanager.LauncherLiveTest
# 최신 frontend build + bootJar 이후
bash scripts/test-runners-live.sh
```
