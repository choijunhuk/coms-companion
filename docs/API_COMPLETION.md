# 동기화 API 보충 계약

[API.md](API.md)의 운영자 응답 API에 적용되는 저장·재시도 세부 규칙입니다. 모든 운영자 경로는 Bearer 인증을 요구하고, 쓰기 요청은 UUID `operationId`로 재시도 영수증을 구분합니다.

## 최초 응답 이관과 충돌

`POST /api/admin/events` 생성 요청(`expectedVersion: 0`)은 선택 필드 `initialResponses`로 수동 참석 응답을 함께 올릴 수 있습니다. 최대 500건이며 각 행은 `{id,operationId,memberId,name,rsvp,noticeAcknowledged,selectedOptions}`입니다. 응답 ID와 회원 ID는 요청 안에서 중복되면 안 됩니다. 행사와 초기 응답은 한 서버 트랜잭션에 저장되고, 서버는 받아들인 응답 ID·버전·회원 ID를 돌려줍니다. Android는 이 목록에서 실제 응답만 이관하며 `미응답` attendance-only 기록은 제외합니다. 이미 존재하는 행사의 업데이트에는 최초 이관 목록을 붙일 수 없습니다.

게시 후 운영자 제안은 `POST /api/admin/events/:id/submissions`에서 생성합니다. 회원의 기존 응답이 있으면 덮어쓰지 않고 `409`와 현재 응답을 돌려줍니다. 링크 연결은 별도 명시적 작업입니다. 행사/응답 버전 충돌도 최신 값을 돌려주며 운영자 앱이 서버 값 사용 또는 새 기준 버전으로 재제안을 선택합니다.

응답의 내부 `source` 값은 `operator` 또는 `member`로 저장됩니다. 최초 수동 이관은 별도 원본 작업 ID도 보존합니다. 실제 행사 출석은 온라인 RSVP와 별개로 Android 로컬에 기록합니다.

## 변경 커서

`GET /api/admin/changes?cursor=N`은 `{changes,nextCursor,hasMore}`를 반환합니다. 각 변경은 `{sequence,eventId,kind,entityId,memberId,payload}` 형식이고 `kind`는 `event`, `submission`, `payment` 중 하나입니다. 순서대로 최대 100건씩 읽습니다. Android는 응답 upsert와 커서를 같은 Room 트랜잭션에 반영합니다. 요청 커서가 서버의 현재 최대 sequence보다 크면 `409 CURSOR_RESET`을 반환하므로 전체 스냅샷을 다시 읽어 ID 기준으로 반영합니다.

서버는 기존 events/submissions/payments를 초기화하지 않고 변경 테이블을 추가할 때 현재 레코드의 커서를 한 번 채웁니다. SQLite `user_version`은 링크 취소 관계 migration 후 3입니다.

## 초대 취소

- `POST /api/admin/events/:id/links/revoke`: `{operationId,issuedOperationId}`. `issuedOperationId`는 기존 링크 발급 작업입니다. 서버가 그 작업 영수증을 확인하고 해당 행사의 링크만 취소합니다. 공통 링크에서 파생된 자기 응답 수정 링크도 함께 취소합니다. 재시도에는 같은 `operationId`를 사용합니다.
- `POST /api/admin/events/:id/links/revoke-member`: `{operationId,memberId}`. 해당 행사에서 지정 회원의 개인 링크와 그 회원에 연결된 공통 제출 수정 링크를 취소하고 `{revokedCount}`를 돌려줍니다. 다른 행사나 다른 회원 링크에는 영향을 주지 않습니다. Android 회원의 탈퇴 처리도 이 취소를 대기함에 추가합니다.
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

서버는 공개 배포된 상태가 아닙니다. `PUBLIC_BASE_URL`은 origin만 허용하고 비로컬 HTTP를 거부합니다. HTTPS 도메인과 운영 자격 증명을 정하기 전에는 실제 회원에게 링크를 발송하지 않습니다.
