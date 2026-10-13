# COM’s Companion

동아리 운영자가 Android에서 회원·행사·회비·연락·운영 기록을 관리하고, 회원은 작은 웹에서 참석·일정 투표·안내 확인·입금 확인 요청을 제출하는 앱입니다. Android 로컬 업무와 회원 응답 서버는 각각 Room과 SQLite에 저장합니다. 첫 실행에는 실제 회원 예시를 넣지 않으며 기본 기능은 템플릿 치환으로 동작합니다. 외부 LLM 크레딧이나 문자 발송 API를 사용하지 않습니다.

## 기능

- Android: 5개 기본 탭, 회원/CSV 검토, 행사·수동 출석과 일정 투표, 회비 원장과 입금 요청 검토, 연락 대상·문구 검토, 할 일·알림, 운영비·물품·모집·회의 기록.
- 보호: 휴대폰 화면 잠금 인증을 사용하는 선택형 앱 잠금, 암호화된 `.comsbak` 파일 백업/미리보기/복원과 기존 자료 복구 파일. 백업은 앱 데이터를 보존하지만 서버 인증 정보는 제외합니다.
- 동기화: 최초 수동 참석 응답 이관, 변경 커서와 영속 작업 대기함, 충돌 검토·재시도, 공통/개인 링크 발급·취소.
- 웹/서버: 참석·안내 확인·일정 투표·입금 확인 요청, 불확실 저장 복구, Fastify와 SQLite 서버의 인증·권한·버전 검사.
- 문자와 공유: 문자는 번호와 본문을 기본 문자 앱에 전달해 운영자가 직접 전송합니다. Kakao 키가 없으면 Android 일반 공유를 제공합니다. 외부 앱 실행은 발송 완료로 기록하지 않습니다.

앱 기능의 검증 단계와 외부 확인 조건은 [STATUS](docs/STATUS.md), 화면 명세는 [UI·UX](docs/UI_UX.md), 데이터/동기화 구조는 [ARCHITECTURE](docs/ARCHITECTURE.md), API는 [API](docs/API.md)를 확인하세요.

## Android 빌드

JDK 17과 Android SDK 36이 필요합니다. [Android 안내](apps/android/README.md)를 따라 `apps/android/local.properties.example`을 `local.properties`로 복사하고 `sdk.dir`에 로컬 SDK 경로를 지정합니다.

```sh
cd apps/android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK는 `apps/android/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 디버그 APK 빌드와 설치·실행, 에뮬레이터, 실기 확인은 서로 다른 검증입니다. 실제 SKT 문자 전송과 Kakao 공유는 실기/실전송으로 검증하지 않았습니다.

## 웹·서버 실행

Node 24.21.0과 npm이 필요합니다. SQLite는 Node 내장 모듈을 사용합니다.

```sh
nvm use
npm ci
npm run check
cp .env.example .env
```

`.env`의 `ADMIN_TOKEN`은 32자 이상의 무작위 비밀값, `SERVER_ENCRYPTION_KEY`는 무작위 32바이트의 64자리 hex로 설정합니다. 두 값과 데이터베이스는 공개 Git에 넣지 않습니다. 암호화 키를 바꾸면 기존 재시도 영수증을 복호화할 수 없으므로 보존해야 합니다.

```sh
npm start
```

기본 주소는 `http://localhost:3000`이며 빌드된 회원 웹과 API를 함께 제공합니다. 개발 서버는 `npm run dev`, 웹만 실행할 때는 `npm run dev:web`을 사용합니다. 에뮬레이터에서 로컬 서버에 연결할 때 Android 주소는 `http://10.0.2.2:3000`입니다.

현재 운영자가 제공한 도메인은 없습니다. 실제 회원 링크를 공유하려면 HTTPS로 접근 가능한 호스트를 정하고 `PUBLIC_BASE_URL`과 Android 서버 주소를 같은 origin으로 설정해야 합니다. 서버를 공개 배포하거나 기존 COM’s 운영 서비스를 변경하지 않았습니다.

## Kakao 설정

현재 체크한 로컬 프로젝트에서 값이 채워진 Kakao 설정 파일은 발견되지 않았습니다. 설정 예시는 [`apps/android/local.properties.example`](apps/android/local.properties.example)이며 `kakao.nativeAppKey`와 `kakao.shareUrl`이 비어 있습니다. 실제 Kakao Developers 앱 키와 등록 URL을 발급받아 비공개 `apps/android/local.properties`에 설정하기 전에는 공식 SDK 공유가 구성된 것으로 간주하지 않습니다. 설정이 없어도 일반 공유·복사 대안을 사용할 수 있습니다.

## 데이터와 공개 저장소

운영자 데이터는 Android 기기의 Room, 온라인 응답은 서버 SQLite가 소유합니다. 회원의 입금 요청은 납부 증명이 아니며, 운영자가 실제 입금을 확인한 뒤에만 회비 원장에 반영합니다. 서버 인증 정보는 암호화 백업에도 넣지 않습니다. 회원 정보·전화번호·납부 내역·데이터베이스·비밀키·서명키는 공개 저장소에서 제외합니다. 공개 파일 검사는 개인정보 수동 검토를 대신하지 않습니다.

## 이어서 개발

[AGENTS](AGENTS.md), [META_PROMPT](META_PROMPT.md), [STATUS](docs/STATUS.md), [UI·UX](docs/UI_UX.md), [구조](docs/ARCHITECTURE.md), [로드맵](docs/ROADMAP.md), [API](docs/API.md)를 먼저 읽습니다.
