# COM’s Companion

동아리 운영을 한곳에서. Android 운영 앱, 회원 응답 웹, Fastify/SQLite 서버의 첫 구현입니다. 첫 실행은 빈 데이터이며 외부 LLM·유료 문자 API·알림톡을 사용하지 않습니다.

## 지원 기능

- Android: 홈/회원/행사/회비/연락, Room 저장, 회원 편집·필터·CSV 검토, 행사·수동 참석/출석, 회비 청구·납부·면제·환불·정정 이력, 할 일, 템플릿·개인별 미리보기·초안/기록, 연락 알림, 테마 설정.
- 문자: 한 명씩 기본 문자 앱에 번호와 본문 전달. 운영자가 직접 전송하며 앱 전달·사용자 발송 확인을 분리합니다.
- 카카오: 실제 키/도메인 설정이 있을 때 공식 공유 SDK, 미설정 시 일반 공유·복사 대안. SDK 실패는 표시하며 실제 전송 완료를 추정하지 않습니다.
- 회원 웹: 행사 안내·참석 응답·안내 확인·일정 투표·개인 입금 확인 요청, 검토·제출·수정. 불확실 저장은 원래 작업으로 재확인합니다.
- 서버: 인증, 행사/응답 버전, 해시된 초대 토큰, 개인/공통/자기 제출 수정 권한, 만료·취소·마감·정원·충돌·재시도, 명시적 회원 연결/입금 검토 API.
- Android 온라인: 수동 행사 게시·공통/개인 링크 발급·응답 스냅샷 가져오기. 서버 저장 확인 후에만 링크를 표시합니다.

수동 응답 최초 서버 이관, 게시 후 운영자 응답 수정·충돌 검토, 자동 동기화, 앱 잠금, 보호된 백업/복구는 아직 없습니다. 수동 응답이 있는 행사 게시와 게시 후 로컬 응답 수정을 차단합니다. 서버는 공개 배포되지 않았습니다. 최신 검증은 [STATUS](docs/STATUS.md)를 확인하세요.

## Android 실행

JDK17, Android SDK36. [Android 안내](apps/android/README.md)에 따라 local.properties.example을 local.properties로 복사해 SDK 경로를 지정합니다. 실제 Kakao 설정이 있을 때만 키/등록 도메인을 입력합니다.

```sh
cd apps/android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: apps/android/app/build/outputs/apk/debug/app-debug.apk. GitHub Actions android-verification 아티팩트에 성공한 빌드와 보고서를 보관합니다. 실기/실문자 검증을 뜻하지 않습니다.

## 웹·서버 실행

Node24.21.0 LTS와 npm. SQLite는 Node 내장 모듈이며 별도 DB 서비스가 필요 없습니다.

```sh
nvm use
npm ci
npm run check
cp .env.example .env
```

.env의 ADMIN_TOKEN은 32자 이상의 무작위 비밀값, SERVER_ENCRYPTION_KEY는 무작위 32바이트의 64자리 hex로 각각 설정합니다. 환경파일은 비공개로 보관합니다. 암호화 키 변경 시 기존 재시도 영수증을 읽을 수 없으므로 키를 보존하세요.

```sh
npm start
```

기본 http://localhost:3000에서 웹 빌드와 API를 함께 제공합니다. 개발 API는 npm run dev, 웹은 npm run dev:web입니다. Vite 프록시는 기본 로컬 서버에 연결되며 통합 확인은 단일 서버에서 합니다.

실제 HTTPS 호스트를 PUBLIC_BASE_URL과 Android에 동일하게 설정한 뒤에만 링크를 공유합니다. 디버그 에뮬레이터는 http://10.0.2.2:3000을 허용합니다. 공개 소스 게시에 기존 COM’s 서버 변경·공개 배포·실제 회원 발송은 포함되지 않습니다.

## 데이터와 비용

회원/회비 원장은 Android Room, 온라인 응답은 서버 SQLite가 원본입니다. 입금 요청은 주장 기록이며 납부 완료가 아닙니다. 이름/번호 자동 회원 연결, GET/스크랩의 상태 변경은 하지 않습니다.

문자 비용은 가입 요금제, 카카오 공유는 제공 쿼터, 서버 비용은 배포 환경 기준입니다. 실제 회원 데이터·DB·키·토큰·서명키는 Git에서 제외합니다. npm run audit:public은 추적 파일의 개인 파일/알려진 키 형식을 검사하며 개인정보 수동 검토를 대신하지 않습니다.

현재 앱은 OS 백업을 끄며 파일 백업/복구가 없습니다. 앱 삭제 시 로컬 데이터를 잃을 수 있으므로 실명단 투입 전 보호된 백업과 실기 검증을 완성해야 합니다.

## 이어하기

[AGENTS](AGENTS.md), [META_PROMPT](META_PROMPT.md), [STATUS](docs/STATUS.md), [UI·UX](docs/UI_UX.md), [구조](docs/ARCHITECTURE.md), [로드맵](docs/ROADMAP.md)을 읽고 현재 Git/CI부터 확인합니다. [API](docs/API.md), [선택 버전 근거](docs/SOURCES.md), [이번 계획](docs/IMPLEMENTATION_PLAN.md)을 유지합니다.
