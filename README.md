# Server Manager

macOS에서 Minecraft 1.21.1 / NeoForge 서버를 실행하고 콘솔과 파일을 관리하는 로컬 웹 프로그램입니다. Kotlin/Spring Boot와 React/TypeScript로 구현했습니다.

패스키 로그인과 외부 접속 설정은 [SECURITY.md](SECURITY.md)를 참고하세요. 공개 URL은 기본적으로 빈 값이며 nginx는 직접 구성합니다.

공개 저장소: [yoonseo6399/ServerManager](https://github.com/yoonseo6399/ServerManager) · [최신 설치 파일](https://github.com/yoonseo6399/ServerManager/releases/latest)

## macOS 설치

`dist/ServerManager-0.3.0-universal.pkg`에 Intel·Apple Silicon Java 21이 포함되어 있습니다. 설치 후 응용 프로그램의 **Server Manager**를 실행하세요. 자동 시작 토글과 데이터 위치는 [INSTALL.md](INSTALL.md)를 참고하세요.

## 소스에서 실행

Java 21, Node.js 20.19+ 또는 22.12+가 필요합니다. 첫 빌드와 NeoForge 설치에는 인터넷 연결이 필요합니다.

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

1. **서버 만들기**에서 이름, 공식 NeoForge 버전, Java 21 경로, 게임 포트, 최대 힙 메모리를 선택합니다.
2. **NeoForge 설치**를 누르고 콘솔의 설치 진행을 확인합니다.
3. 설치 완료 후 **시작**을 누릅니다. `Done` 로그를 확인하면 같은 Mac의 Minecraft 클라이언트에서 `127.0.0.1:설정포트`로 접속할 수 있습니다.
4. 콘솔에 `list`, `say hello` 등을 입력합니다. 명령과 출력은 별도로 표시됩니다.
5. **종료** 후 파일 관리자에서 설정 파일을 편집하거나 모드 파일을 업로드합니다.

요청자의 명시적 지시에 따라 이 로컬 버전은 새 서버 생성 시 Minecraft EULA에 자동 동의하고 시각을 `eula.txt`에 기록합니다. EULA: https://aka.ms/MinecraftEULA

## 구현된 기능

- 독립 인스턴스 생성, NeoForge 공식 버전 조회·설치, 설치기 SHA-256 검증
- 여러 서버 실행, 정상 종료·재시작·강제 종료, 포트 충돌 검사
- 실제 물리 메모리에 따른 동적 힙 상한: `물리 메모리 − max(1 GiB, 물리 메모리의 10%)`
- 시작 시 다른 활성 인스턴스의 최대 힙 합산 검사. OS의 현재 여유 메모리는 변동하는 참고값으로 별도 표시
- 입력·출력 구분, 최근 300줄부터 동적 조회, 이전 로그 페이지 로딩, 화면에 보이는 행만 렌더링, 검색·오류 필터·자동 스크롤, StackTrace 프레임 접기, Mixin 관련 로그 분리
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
  server/             # 월드, mods, 설정, NeoForge 런타임
  console.log         # 원본 프로세스 출력 (실행 간 누적)
  backups/            # 덮어쓰기 전 파일 원본
  trash/              # UI에서 삭제한 파일 및 폴더
```

휴지통과 백업은 현재 로컬 폴더에서 직접 복구합니다. UI 복원, 전체 월드 백업, 로그 순환은 후속 범위입니다. 라이브 로그는 최근 10,000개까지 메모리에 유지하고 원본 출력은 디스크에 보존합니다.

파일 쓰기는 설치·실행·종료 중 차단합니다. 텍스트 편집은 2 MiB 이하, 업로드는 요청당 256 MiB까지 지원합니다. 다음 시작 시 UI의 포트와 로컬 전용 바인딩이 `server.properties`에 반영되므로 해당 값은 **서버 설정 UI**에서 바꾸세요.

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

기본적으로 관리 웹과 게임 서버는 **127.0.0.1** 에 바인딩합니다. 관리 웹은 공개 HTTPS URL을 설정한 뒤 수신 주소를 변경할 수 있습니다. 패스키 인증·초대·권한·감사 기록을 적용했습니다. HTTPS와 nginx 라우팅은 사용자 설정이며, 다중 Mac 에이전트·모드 저장소 동기화는 후속 범위입니다. 설치본에는 Java가 포함됩니다. mrpack은 Minecraft 1.21.1 + NeoForge 팩의 검사·해시 확인·서버 overrides 적용·로더 설치를 지원합니다. Mixin 표시는 관련 로그 모음이며 모드별 원인 진단기는 아닙니다.

현재 여유 물리 메모리는 Java의 실제 네이티브 메모리 사용량이나 다른 앱의 향후 사용량을 보장하지 않습니다. 힙 외 오버헤드에 대한 여유는 위 예산으로 확보하되, 서버가 많으면 힙을 더 낮춰 사용하세요.

정상 종료는 월드 저장을 기다립니다. 강제 종료나 관리 프로세스의 비정상 종료 후에는 남아 있는 Java 프로세스·포트를 확인해야 합니다. 다음 시작의 포트 검사는 중복 실행을 막지만 아직 프로세스 재연결은 지원하지 않습니다.
