# First implementation plan

**Goal:** 명세의 오프라인 Android 업무와 제한된 온라인 회원 응답을 실제 저장·수정·실행 가능한 첫 버전으로 만든다.
**Architecture:** Android Room은 회원과 회비 원장 원본이다. Fastify/SQLite는 게시된 행사와 회원 제출만 소유한다. Vite 웹과 API를 같은 서버에서 제공한다.
**Spec:** META_PROMPT.md, docs/UI_UX.md, docs/ARCHITECTURE.md, DESIGN.md.

## Global constraints
5개 탭, 초기 라이트 테마, 템플릿 치환으로 동작. SMS ACTION_SENDTO 한 명씩, 공식 Kakao 공유 또는 일반 공유·복사. 실제 전송 상태 추정 금지. 개인정보·키·DB·서명키 게시 금지. 외부 서비스 배포·실제 수신자 발송은 수행하지 않는다.

## Review focus
입금 주장의 자동 원장 반영 금지; 중복 재시도와 버전 충돌; GET/미리보기의 상태 변경 금지; 개인 토큰의 행사/회원/동작 제한; 연락 준비 후 탈퇴/납부한 대상 재검사.

## Task 1 — Public source baseline
- [ ] 첨부 문서를 안전하게 해제하고 인증 계정/기존 원격/동명 저장소 확인.
- [ ] 제외 규칙·실행 계획을 커밋하고 Public origin을 생성·푸시.
- [ ] 실제 URL/visibility/main/commit/주요 파일 확인.

## Task 2 — Android offline workflow
Files: apps/android/**. Owner: Android executor.
- [ ] 빌드 가능한 Gradle/Kotlin/Compose/Room/DataStore 프로젝트와 5개 탭.
- [ ] 회원·행사·회비 청구/납부/정정 이력·할 일·템플릿 저장/수정.
- [ ] 개인별 치환·대상 제외·문자/공유/복사·정직한 연락 기록·알림.
- [ ] 금액/대상/치환 테스트, unit/lint/APK 검증. 재시작·입력·좁은 화면 검증은 실행 가능한 기기 환경에 한해 기록.

## Task 3 — Restricted response API
Files: apps/server/**, docs/API.md. Owner: leader.
- [ ] 운영자 인증, 행사 게시와 버전, 개인/공통 초대, 토큰 해시, SQLite 영속 저장.
- [ ] 참석/안내 확인/투표/입금 주장, 마감/정원/취소/만료/동시 수정/재시도 방어.
- [ ] 변경 조회·공통 신청 연결 검토. 실제 납부 원장과 별개.
- [ ] Fastify inject 기반 보안/저장/수정/재시작 테스트.

## Task 4 — Member mobile web
Files: apps/web/**. Owner: web executor. Interface: docs/API.md.
- [ ] 행사 상세·입력·검토·완료·수정, 공통/개인 링크 권한 구분.
- [ ] 실패 시 작성 보존·수동 재시도·중복 클릭 차단·마감/만료/충돌 설명.
- [ ] Typecheck/build/tests, 가능한 로컬 브라우저 확인 및 디자인 대비 검증.

## Task 5 — Integrate and handoff
- [ ] Android 온라인 설정·게시·응답 가져오기 연결. 진행 상황에 따라 남은 작업을 명시하고 미완성 기능의 사용 가능 표시 금지.
- [ ] 독립 리뷰 후 필요한 수정·재검증. README/STATUS/SOURCES/API를 실제 지원 범위로 갱신.
- [ ] 의미 있는 기능 단위 Lore 커밋·푸시, 원격과 비밀값 제외 최종 확인.

## Verification and stop condition
구현한 단위는 fresh tests/build를 통과하고 저장/수정/실패 복구를 갖춘다. 로컬 성공과 실제 SKT/카카오/외부 배포 검증을 구별한다. 실기 연결·기존 서버 배포·확장 기능은 다음 세션 조건으로 기록한다. 사용자에게 이미 승인된 구현을 재승인 요청하지 않는다.
