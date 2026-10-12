# Design

## Source of truth
Status: Active. Last refreshed: 2026-10-12.
Primary surfaces: Android 운영 앱, 회원 응답 웹.
Evidence: META_PROMPT.md, docs/UI_UX.md, docs/ARCHITECTURE.md, docs/ROADMAP.md.
The supplied specification takes precedence over this implementation checklist.

## Brand
차분하고 실용적인 COM’s 텍스트 워드마크. 장식·그라데이션·가상 통계 없이 실제 정보와 다음 행동을 보여 준다.

## Product goals
오프라인 회원·행사·회비·할 일 관리, 무료 템플릿 기반 개인 안내 준비, 사용자가 실행하는 외부 공유, 앱 설치 없는 회원 응답.
자동 발송·유료 AI·전체 관리자 웹·기존 운영 서버 변경은 이번 구현의 범위가 아니다.

## Personas and jobs
SKT Android 동아리 운영자는 업무를 조회·수정·확인한다. 부원은 모바일 링크에서 참석·안내 확인·투표·입금 주장만 제출한다.

## Information architecture
Android: 홈 / 회원 / 행사 / 회비 / 연락, 우측 상단 설정. 홈에 할 일. 웹: 행사 상세 → 입력 → 최종 확인 → 저장 확인 → 수정.

## Design principles
저장된 실제 데이터, 명확한 다음 행동, 정직한 연결·발송 상태. 금액·응답의 변경 전후와 작성 주체를 보존한다.

## Visual language
토큰은 docs/UI_UX.md §2를 그대로 사용한다. 시스템 한국어 글꼴; 24/18/16/14/12sp; 8dp 간격; 화면 20dp, 카드 16dp, 모서리 16dp. 버튼 52dp, 모든 터치 영역 최소 48dp. 금융 화면에 게임 요소를 넣지 않는다.

## Components
Compose Material 3의 목록·입력·대화상자·탭·스낵바를 재사용한다. 웹은 시맨틱 HTML과 CSS 토큰을 사용한다. 로딩·빈 상태·실패·저장 확인을 같은 데이터 흐름에서 표시한다.

## Accessibility
본문 대비 4.5:1, 큰 글자 대비 3:1을 계산한다. 아이콘에 이름, 입력에 label, 오류에 읽기 가능한 설명. 키보드 포커스·글자 확대·상태 문구를 지원한다.

## Responsive behavior
Android 최소 16dp 여백과 스크롤/키보드 인셋. 회원 웹 최대 640px, 320px에서도 수평 스크롤 없이 사용.

## Interaction states
서버 미연결이면 로컬 기능을 계속 제공하고 공개 링크를 발급하지 않는다. 오류 시 작성 내용을 보존하며 재시도한다. 외부 앱 실행을 전송 성공으로 기록하지 않는다.

## Content voice
한국어 실무 문구. ‘입금 확인 요청’은 ‘납부 완료’와 구별한다. 템플릿은 ‘AI 호출 없음’, 문자는 ‘통신사 요금제 적용’으로 설명한다.

## Implementation constraints
Kotlin/Compose/Room/DataStore, React/TypeScript/Vite, Node/TypeScript/Fastify/SQLite. 앱 데이터는 초기 빈 상태이며 테스트만 가상 예시를 사용한다. 키·서명키·DB·실제 회원 정보는 Git에서 제외한다. 시각 검토는 docs/UI_UX.md 기준으로 기록하며 제공된 이미지 기준은 없다.

## Open questions
실제 SKT 요금제·기종·SIM 구성, Kakao 앱 키/키 해시/도메인, 공개 배포 대상은 실연동 검증 때 필요하다. 로컬 구현을 차단하지 않는다.
