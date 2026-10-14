# COMS 서버의 Companion 배포

배포 대상은 `https://coms.kw.ac.kr/companion/`이다. 기존 서버·도메인·TLS를 사용하며 새 유료 클라우드나 문자 API를 활성화하지 않는다. 기존 COMS 웹에 로그인 복귀 경로와 본인 확인 endpoint를 추가하며 PostgreSQL 회원 테이블·계정 권한·배포 프로젝트는 유지한다.

## 파일과 실행 경계

- Companion 저장소의 `Dockerfile`, `deploy/compose.yaml`이 Node24.21.0·웹·응답 API를 빌드한다. 새로운 npm 의존성은 없다.
- 서버 작업 경로는 `/home/kw/apps/coms-companion/`이며 `releases/<commit>`과 `current` 링크로 코드를 구분한다.
- `.env`는 권한0600으로 별도 보관한다. `ADMIN_TOKEN`, `SERVER_ENCRYPTION_KEY`는 기존 COMS 자격 증명과 별개다. 코드·이미지·Git에 포함하지 않는다.
- 응답 SQLite는 `data/`, 회원 명부 읽기 스냅샷은 `roster/`에 보존한다. 컨테이너 업데이트로 제거하지 않는다. 두 디렉터리는 권한0700이며 명부 파일은0600이다.
- Docker의 `node` 사용자 UID1000과 수집기 `kw` UID1000을 일치시킨다. 명부 디렉터리는 컨테이너에 읽기 전용으로 연결한다.
- 컨테이너는 Linux 호스트 네트워크에서 `127.0.0.1:3200`만 사용하며 메모리512MiB/CPU1로 제한한다. 외부 요청은 기존 Nginx의 `/companion/` 프록시로만 전달한다.

## 설정

`PUBLIC_BASE_URL=https://coms.kw.ac.kr/companion`은 웹 빌드와 서버에 동일하게 적용한다. 발급 링크는 `/companion/respond#토큰`이며, 토큰은 URL fragment로 보관하고 API의 Bearer 헤더로 전달한다. 로그나 query string에 넣지 않는다.

비공개 환경파일에는 `COMPANION_ENV_FILE`, `COMPANION_DATA_DIR`, `COMPANION_ROSTER_DIR`의 절대 경로와 `SOURCE_COMMIT`을 설정한다. `deploy/compose.yaml`은 서버의 `HOST=127.0.0.1`, `PORT=3200`, `TRUST_PROXY=loopback`, `DATABASE_PATH=/app/data/companion.sqlite`, `ROSTER_PATH=/app/roster/eligible-members.json`을 고정한다.

Nginx의 기존 COMS HTTPS server 블록에 `deploy/nginx-companion.conf` 내용만 포함한다. `/companion`은 `/companion/`으로 이동하고 프록시는 경로 앞부분을 제거한다. `X-Forwarded-For`는 클라이언트 제공 값 대신 `$remote_addr`로 교체한다. 서버는 직접 연결된 루프백 프록시만 신뢰한다. 설정을 백업하고 변경 직전 해시를 대조하며, `nginx -t` 성공 후에만 reload한다.

## 회원 명부

`deploy/refresh-roster.py`는 기존 `coms-website-db-1` 안에서 읽기 전용 트랜잭션으로 `eligible_members`의 고유 등록 ID·선택 학번·이름·전화번호·기수만 읽는다. 비밀번호·이메일·인증키·역할·메모를 내보내지 않는다. 원본 DB와 계정 권한은 변경하지 않는다.

`deploy/coms-companion-roster.service`와 timer는 5분 간격으로 비공개 스냅샷을 갱신한다. API와 동일한 크기·필드·문자 검증을 통과한 결과만 원자적으로 교체하며, 실패하면 마지막 정상 파일을 유지한다. 운영자 Bearer 인증이 있는 `GET /companion/api/admin/roster`만 이 스냅샷을 읽을 수 있다. 일반 회원 링크는 전체 명부 권한을 주지 않는다.

앱 설정의 온라인 응답 연결에서 서버 주소·운영자 토큰을 설정한 뒤 **COMS 회원 명부 확인 → 서버 명부 불러오기 → 미리보기 → 이 기기에 적용**한다. 원본 등록 ID 기반의 고정 UUID로 반복 적용의 중복을 막으며 학번이 없는 회원도 포함한다. 이름·번호·기수만 갱신하고 기존 수신 제외·활동 상태·소모임·학기·역할·회비 기록과 이 기기에만 있는 회원을 유지한다. 원본 행을 삭제·재생성하면 새 등록 ID로 취급하므로 이름·번호로 자동 병합하지 않는다. 원본의 전화번호는 발송 동의를 의미하지 않는다.

## 기존 COMS 로그인

COMS 웹의 인증된 `GET /api/companion/me`와 로그인/회원가입의 Companion 복귀 경로를 먼저 배포한다. Companion의 비공개 환경파일에 `COMS_AUTH_URL=http://127.0.0.1:8080/api/companion/me`를 설정한다. Node 서버는 COMS의 JWT 비밀키·비밀번호를 복사하지 않고 로그인 `token` 쿠키만 검증 endpoint에 전달한다. 익명·만료 세션은401, 로그인됐지만 명부 연결이 없는 계정은403이어야 한다. 검증 API 장애·명부 읽기 실패에서는 미확인 응답으로 우회하지 않는다.

원본 명부 ID로 회원 UUID를 만들므로 휴대폰에서 명부를 적용해야 응답이 기존 회원 행에 표시된다. 명부의 모든 행에 가입 계정이 존재한다고 가정하지 않는다. 일반적인 학생 계정은 원본의 정확한 계정 ID로, 졸업생은 가입 과정에서 부여된 고유 계정 ID와 원본 등록 ID·입학년도를 검사한다. 시스템 운영 계정에 임의의 회원을 연결하지 않는다.

로그인 복귀의 초대 토큰은 URL fragment로 유지한다. 카카오 인앱 브라우저에서 기존 브라우저의 로그인 상태를 사용할 수 없다면 해당 브라우저에서 로그인한다. 회원의 이름 입력만으로 본인 확인하지 않는다. 회원 응답 권한 철회는 행사별 SQLite 기록으로 유지하며 운영자가 같은 회원의 개인 링크를 명시적으로 재발급할 때만 다시 허용한다.

v3에서는 회원별 철회가 링크 취소로만 기록되므로 과거 철회 목록을 v4의 회원별 기록으로 자동 복원할 수 없다. 기존 철회를 사용한 서버는 명시적으로 해당 행사·회원의 철회를 재적용한 뒤 COMS 공통 인증을 활성화한다. 이번 운영 배포의 사전 확인에서는 행사·응답·초대·취소 링크가 모두0건이었다.

## 배포와 복구

1. 테스트·타입 검사·빌드·공개 파일 검사를 통과한 커밋만 새 release 폴더로 전송한다. 비공개 설정·회원 파일·DB·Android 산출물은 전송 목록에서 제외한다. 새 release의 공개 소스는 Node 사용자도 읽을 수 있도록 a+rX 권한을 확인한다. 비공개 환경파일0600·데이터 디렉터리0700은 그대로 유지한다.
2. 명부 수집기를 먼저 실행하고, `docker compose --env-file <private-env> -f <release>/deploy/compose.yaml config -q`로 설정을 확인한다.
3. 이미지를 빌드하고 기존 release·이미지 식별자를 보관한다. 전용 Compose 프로젝트의 `up -d`와 health check를 확인한다.
4. Nginx 변경은 `/companion/` 블록에 한정한다. 공개 HTTPS의 웹·파일·API 인증·응답 저장과 기존 COMS 홈을 검증한다.
5. 앱을 데이터 보존 업데이트하고 서버 주소를 연결한다. Kakao 제품 링크 도메인에 `https://coms.kw.ac.kr`을 등록하며, 일반 공지는 `/companion/`, 승인된 행사 공통 링크가 있는 공지는 해당 `/companion/respond#...`를 연다.

문제가 있으면 이전 `current`와 Compose 이미지로 되돌린다. DB·암호화 키·명부 스냅샷을 삭제하지 않는다. Nginx도 백업과 현재 해시를 대조해 이 배포의 블록만 되돌리고, syntax 검사 후 reload한다. 새 명부 API만 중단할 때는 timer를 중단해도 응답 웹의 기존 자료는 유지된다.

현재 실행 결과와 미검증 항목은 [STATUS.md](STATUS.md)에 별도로 기록한다.
