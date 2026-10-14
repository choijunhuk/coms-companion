# 동기화 API 보충 계약

[API.md](API.md)의 운영자 응답 API에 적용되는 저장·재시도 세부 규칙입니다. 모든 운영자 경로는 Bearer 인증을 요구하고, 쓰기 요청은 UUID `operationId`로 재시도 영수증을 구분합니다.

## 최초 응답 이관과 충돌

`POST /api/admin/events` 생성 요청(`expectedVersion: 0`)은 선택 필드 `initialResponses`로 수동 참석 응답을 함께 올릴 수 있습니다. 최대 500건이며 각 행은 `{id,operationId,memberId,name,rsvp,noticeAcknowledged,selectedOptions}`입니다. 응답 ID와 회원 ID는 요청 안에서 중복되면 안 됩니다. 행사와 초기 응답은 한 서버 트랜잭션에 저장되고, 서버는 받아들인 응답 ID·버전·회원 ID를 돌려줍니다. Android는 이 목록에서 실제 응답만 이관하며 `미응답` attendance-only 기록은 제외합니다. 이미 존재하는 행사의 업데이트에는 최초 이관 목록을 붙일 수 없습니다.

게시 후 운영자 제안은 `POST /api/admin/events/:id/submissions`에서 생성합니다. 회원의 기존 응답이 있으면 덮어쓰지 않고 `409`와 현재 응답을 돌려줍니다. 링크 연결은 별도 명시적 작업입니다. 행사/응답 버전 충돌도 최신 값을 돌려주며 운영자 앱이 서버 값 사용 또는 새 기준 버전으로 재제안을 선택합니다.

응답의 내부 `source` 값은 `operator` 또는 `member`로 저장됩니다. 최초 수동 이관은 별도 원본 작업 ID도 보존합니다. 실제 행사 출석은 온라인 RSVP와 별개로 Android 로컬에 기록합니다.

## 변경 커서

`GET /api/admin/changes?cursor=N`은 `{changes,nextCursor,hasMore}`를 반환합니다. 각 변경은 `{sequence,eventId,kind,entityId,memberId,payload}` 형식이고 `kind`는 `event`, `submission`, `payment` 중 하나입니다. 순서대로 최대 100건씩 읽습니다. Android는 응답 upsert와 커서를 같은 Room 트랜잭션에 반영합니다. 요청 커서가 서버의 현재 최대 sequence보다 크면 `409 CURSOR_RESET`을 반환하므로 전체 스냅샷을 다시 읽어 ID 기준으로 반영합니다.

서버는 기존 events/submissions/payments를 초기화하지 않고 변경 테이블을 추가할 때 현재 레코드의 커서를 한 번 채웁니다. SQLite `user_version`은 링크 취소 관계와 행사별 회원 철회 테이블 추가 후 4입니다.

## 초대 취소

- `POST /api/admin/events/:id/links/revoke`: `{operationId,issuedOperationId}`. `issuedOperationId`는 기존 링크 발급 작업입니다. 서버가 그 작업 영수증을 확인하고 해당 행사의 링크만 취소합니다. 공통 링크에서 파생된 자기 응답 수정 링크도 함께 취소합니다. 재시도에는 같은 `operationId`를 사용합니다.
- `POST /api/admin/events/:id/links/revoke-member`: `{operationId,memberId}`. 해당 행사에서 지정 회원의 개인 링크와 그 회원에 연결된 공통 제출 수정 링크를 취소하고 `{revokedCount}`를 돌려줍니다. 다른 행사나 다른 회원 링크에는 영향을 주지 않습니다. COMS 공통 응답의 회원 권한도 행사별로 철회하며 재시작 후에도 유지합니다. 같은 회원의 개인 링크를 운영자가 명시적으로 다시 발급하면 해당 행사 철회 상태를 해제합니다. 공통 링크 재발급만으로는 해제하지 않습니다. Android 회원의 탈퇴 처리도 이 취소를 대기함에 추가합니다.
- `POST /api/admin/links/:id/revoke`: `{operationId}`. 링크 UUID를 직접 지정하는 호환 경로이며 공통 링크 파생 수정 권한도 취소합니다.

서버 migration은 기존 공통 제출 작업 영수증과 토큰 해시를 사용해 이미 파생된 수정 링크를 부모 공통 링크와 연결합니다. 신규 공통 응답은 생성 시 부모 관계를 기록합니다. Android는 취소 요청을 대기함에 보관하고 서버 확인 전 해당 링크 복사를 차단합니다. 네트워크 실패는 취소 완료로 표시하지 않습니다.

## 입금 요청 검토

회원 입금 요청은 주장 기록입니다. 운영자용 prepare/commit/reject 응답의 `request`에는 실제 회원 `memberId`가 포함됩니다. 회원용 GET 응답에서는 이 연결 ID를 숨깁니다. 검토에는 고유 `reviewId`, `ledgerEntryId`, `chargeId`, `ownerId`, 실제 확인 금액과 사유를 사용합니다.

1. `.../review/prepare`가 요청을 해당 원장 ID/owner에 예약합니다. 공개 회원 응답은 계속 `pending`이고 예약 금액·내부 ID를 노출하지 않습니다. 같은 예약의 재요청은 같은 영수증을 반환하고 다른 예약은 `409`입니다.
2. Android가 같은 `ledgerEntryId`를 Room 원장에 한 번 적용하고 로컬 commit 작업을 보존합니다. 금액은 요청액에서 자동 복사하지 않고 운영자가 실제 입금과 대조합니다.
3. `.../review/commit`은 일치하는 예약을 확정합니다. 확정 후 회원 공개 응답에는 `confirmedAmount`만 포함될 수 있습니다. owner, charge, ledger, review ID는 운영자 전용입니다.

앱 복구 시 동일 owner와 서버 영수증을 확인해 원장 누락을 복원할 수 있습니다. 이전 버전에서 봉인된 prepare 작업에 `memberId`가 없으면 그 작업의 operation ID와 본문은 바꾸지 않습니다. 서버의 정확한 요청 ID가 여전히 `pending`이고 같은 예약이 `reserved`인 것을 확인한 뒤 `reviewId`, `ledgerEntryId`, `chargeId`, `confirmedAmount`, `ownerId`, `reason` 여섯 값이 원래 의도와 모두 맞을 때만 연결 회원을 얻어 원장 복구를 진행합니다. 예약이 다른 owner에 속하거나 식별자가 다르면 원장 효과를 적용하지 않고 충돌로 둡니다. 기존 검토 경로는 반려용이며 `confirmed`는 `REVIEW_PREPARE_REQUIRED`로 거부됩니다. 반려도 예약/확정된 요청에는 적용되지 않습니다.

## 오래된 백업의 서버 행사

현재 서버에는 있지만 복원한 Room 백업에는 없는 행사는 설정의 동기화 검토 화면에 `event-recover` 충돌 대기 작업으로 둡니다. 운영자가 서버 출처·안정된 행사 ID·행사 메타데이터/버전을 재확인해 승인하면 `Event`와 `responsesJson`만 복구합니다. 회원, 회비 항목, 납부 원장, 현장 출석 기록을 만들거나 추정하지 않습니다. 검토가 끝날 때까지 전체 변경 커서를 앞으로 이동하지 않아 다음 동기화가 빠진 레코드를 다시 읽습니다. 서버 출처나 행사 메타데이터가 달라지면 새 검토가 필요합니다.

## 배포 경계

`PUBLIC_BASE_URL`은 HTTPS origin과 선택적인 안전한 경로(`/companion` 등)를 허용하고 비로컬 HTTP, 인증정보·query·fragment·경로 이동 구문을 거부합니다. 프록시 뒤의 내부 API 경로는 기존 `/api/...`를 유지하며 발급 링크에 공개 경로를 포함합니다. Origin 검사는 경로를 제외한 scheme·host·port를 비교합니다. 실제 배포·기기 수용은 STATUS.md를 기준으로 하며 링크를 만든 사실만으로 실제 발송을 표시하지 않습니다.

`GET /api/admin/roster`는 기존 운영자 Bearer 인증을 요구합니다. 선택한 비공개 명부 스냅샷에서 `{source:'coms-eligible-members',updatedAt,members:[{sourceId,studentId,name,phone,generation}]}`만 반환합니다. 최대1MiB/5,000행, 중복·빈 원본 등록 ID와 빈 이름·잘못된 형식을 거부합니다. 학번은 없거나 중복될 수 있으며 식별에 사용하지 않습니다. 파일이 없거나 올바르지 않으면 개인정보 없는 `503 ROSTER_UNAVAILABLE`을 반환합니다. COMS DB를 수정하거나 일반 회원 링크에 명부 권한을 부여하지 않습니다.

## COMS 로그인으로 공통 응답 자동 연결

`COMS_AUTH_URL=http://127.0.0.1:8080/api/companion/me`를 설정하면 공통 링크의 GET/POST는 기존 COMS 로그인 쿠키를 검증합니다. 고정 endpoint만 허용하고 초대 Bearer·운영자 토큰·다른 쿠키는 전달하지 않습니다. COMS endpoint는 로그인·차단·이메일 인증과 등록 명부의 정확한 계정 연결을 검사하고 `{source,sourceId,name}`만 반환합니다. 인증된 계정의 학번 직접 일치 또는 검증된 졸업생 계정 ID만 사용하며 이름·전화번호로 추정하지 않습니다.

- GET의 `access.identity`는 `{provider:'coms',name,memberId}`입니다. 같은 공통 링크에서도 해당 회원의 응답만 반환합니다.
- POST에는 그 `memberId`를 `identityMemberId`로 보내 계정 전환을 검사합니다. 인증 근거는 이 입력값이 아니라 COMS 세션입니다. 저장은 `verification:'linked'`와 해당 앱 회원 ID를 사용하며, 이름은 COMS 검증 정보로 고정합니다. COMS 공통 응답에는 별도 수정 Bearer 토큰을 만들지 않습니다.
- 로그인이 필요하면 `401 COMS_LOGIN_REQUIRED`, 등록 회원 연결이 없으면 `403 COMS_MEMBER_REQUIRED`, 해당 행사에서 권한이 철회되면 `403 MEMBER_REVOKED`, 계정 전환은 `409 COMS_ACCOUNT_CHANGED`, 인증 서비스 장애는 `503 COMS_AUTH_UNAVAILABLE`입니다.
- 웹은 기존 `/api/auth/refresh`를 한 번 시도할 수 있으며, 원래 작업 ID와 본문을 유지합니다. 로그인 이동은 `/login?returnTo=%2Fcompanion%2Frespond#초대토큰`으로 토큰을 query에 넣지 않습니다. 가입 후에도 같은 행사로 돌아옵니다.

개인 초대와 이전 비인증 공통 응답의 수정 링크 계약은 유지합니다. 앱은 기존 변경 커서로 연결된 회원 응답을 가져옵니다. 자동 동기화는 설정에서 활성화하며 15분 주기로 예약하고 즉시 가져오기 버튼도 제공합니다.
