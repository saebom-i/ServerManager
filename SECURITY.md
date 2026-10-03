# 외부 접속 보안 설정

관리 기능은 항상 로그인이 필요합니다. 기본 주소는 **http://localhost:8787**이며 `127.0.0.1`에만 바인딩합니다. 공개 URL은 요청에 따라 빈 값으로 남겼고, nginx 라우팅은 변경하지 않았습니다.

## 최초 소유자 등록

1. 최신 앱을 실행합니다.
2. 서버 Mac에서 데이터 폴더의 `security/setup-code.txt`를 열어 일회용 등록 코드를 확인합니다. 기본 위치는 `.local-data/security/setup-code.txt`입니다. 파일은 해당 OS 사용자만 읽을 수 있습니다.
3. 관리 주소에서 코드를 입력하고 패스키를 등록합니다. 코드는 30분간 유효하며 성공 후 삭제됩니다.
4. 다른 관리자는 **계정과 보안 → 사용자 초대**에서 서버별 관리·조회 권한과 함께 초대합니다. 초대 코드는 24시간 동안 한 번 사용할 수 있습니다.

인앱 브라우저에서 패스키가 지원되지 않으면 최신 Safari 또는 Chrome에서 관리 주소를 여세요. 공개 회원가입과 비밀번호 로그인은 없습니다.

## nginx 연결 — 사용자가 직접 구성

`config/application.properties.example`을 `config/application.properties`로 복사합니다. 실제 HTTPS 주소를 준비한 후 아래 값만 지정하세요.

```properties
server-manager.public-origin=https://YOUR-DOMAIN
server.address=127.0.0.1
server.port=8787
```

공개 URL에는 경로를 넣지 않습니다. 외부 포트가 443이 아니면 URL에 포트도 명시합니다. IP 주소는 패스키의 공개 도메인으로 사용하지 않습니다.

Docker nginx에서 호스트의 loopback에 연결할 수 없다면 앱이 수신할 호스트 주소를 명시합니다. `0.0.0.0`도 가능하지만 **공개 HTTPS URL을 먼저 설정해야 시작됩니다.** Docker Desktop의 호스트 이름은 `host.docker.internal`입니다. 다른 Docker 런타임에서는 실제 호스트 주소를 사용하세요.

루프백 밖에서 백엔드에 접속하는 요청에는 `X-ServerManager-Proxy` 헤더가 반드시 필요합니다. 값은 데이터 폴더의 `security/proxy-token.txt`에 생성됩니다. nginx에서 사용자가 보낸 동일 이름 헤더를 그대로 전달하지 말고, 이 값으로 **덮어써야 합니다**.

nginx의 기존 HTTPS server 블록 안에 적용할 location 예:

```nginx
location / {
    proxy_pass http://host.docker.internal:8787;
    proxy_set_header Host YOUR-DOMAIN;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-ServerManager-Proxy "VALUE_FROM_LOCAL_PROXY_TOKEN_FILE";
    proxy_set_header X-Forwarded-For "";
    proxy_set_header Forwarded "";
    client_max_body_size 256m;
    proxy_read_timeout 180s;
}
```

현재 콘솔은 인증된 HTTP 증분 조회를 사용하므로 WebSocket 업그레이드는 필요하지 않습니다. 위 토큰은 프록시 경로 확인용이며 사용자 로그인·권한을 대체하지 않습니다. nginx와 호스트 백엔드 사이의 예시는 같은 Mac의 로컬 경로를 전제로 합니다. 다른 머신 사이로 upstream을 연결할 때는 그 구간에도 TLS를 구성하세요.

공개 URL이 HTTPS이면 세션 쿠키에 Secure를 적용합니다. 이 상태에서는 설정한 HTTPS 주소로 로그인하세요. 전달된 Host/Forwarded 헤더를 이용해 패스키 주소를 자동 결정하지 않습니다. 공유기 포트포워딩과 인증서·nginx 설정은 자동 변경하지 않습니다.

## 패스키 도메인 변경 및 복구

패스키는 도메인에 귀속됩니다. localhost에서 등록한 키는 새 공개 도메인에서 그대로 사용할 수 없습니다. 도메인 변경 또는 소유자 패스키 분실 시:

1. 게임 서버를 정상 종료하고 관리 앱을 종료합니다.
2. 동일한 데이터 디렉터리로 아래 명령을 실행합니다.
3. 앱을 다시 시작하고 새 주소에서 새 등록 코드를 사용합니다.

```bash
./scripts/run-local.sh --recover-owner
```

이 명령은 기존 소유자 패스키와 세션을 폐기합니다. 게임 데이터와 다른 사용자 계정은 보존됩니다. 앱이 같은 인증 저장소를 사용 중이면 복구 명령은 거절됩니다. 사용자별 추가 패스키는 **계정과 보안**에서 등록하며 마지막 패스키는 웹에서 삭제할 수 없습니다.

## 적용된 보호

- WebAuthn의 실제 서명·챌린지·RP ID·Origin 검증 및 사용자 확인 필수
- 만료되는 세션별 일회용 챌린지, 로그인 시 세션 ID 교체
- CSRF 토큰, Origin·cross-site 검사, 로그인 시도 제한
- 30분 미사용·8시간 절대 세션 만료, 권한 변경·계정 비활성화 즉시 세션 폐기
- 초대·권한 변경·패스키 삭제·서버 삭제는 최근 5분 내 재인증 필요
- 서버별 관리·조회 권한을 서버 API에 적용하고 인스턴스 목록도 필터링
- 파일 다운로드·로그·mrpack API에도 권한 검사
- 기본 보안 헤더와 CSP, 프록시 경로 토큰
- 요청 본문·토큰·쿠키를 기록하지 않는 감사 로그

인증 데이터는 `security/accounts.json`, 감사 기록은 `security/audit.jsonl`에 저장됩니다. 파일과 디렉터리에 OS 접근 권한을 제한합니다. 감사 로그는 10 MiB 단위로 최근 파일과 이전 파일을 보존합니다.

서버 관리자 권한은 임의의 모드를 설치·실행할 수 있는 신뢰된 공동 관리자용 권한입니다. 현재 네이티브 게임 프로세스는 악성 모드를 격리하는 가상 머신이 아닙니다.

## 서버 삭제

소유자 계정으로 **서버 → 설정 → 서버 삭제**에서 서버 이름을 정확히 입력하세요. 실행·설치·가져오기 작업 중에는 삭제할 수 없습니다. 삭제하면 해당 서버의 사용자 권한도 제거합니다.

데이터는 `.local-data/deleted-instances/<서버 ID>-<삭제 시각>/`에 보존됩니다. 자동 복원 UI는 아직 없습니다. 수동 복원하려면 관리 앱을 종료하고 보관된 폴더를 원래 서버 ID 이름으로 데이터 폴더 바로 아래에 옮긴 뒤 다시 실행하세요. 동일 게임 포트가 다른 서버에 할당되어 있다면 실행 전에 포트를 변경하고, 사용자 권한은 다시 부여하세요. 디스크 공간은 보관 폴더를 직접 삭제하기 전까지 반환되지 않습니다.
