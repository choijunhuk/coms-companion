# 회원 응답 API

회원 웹과 운영자 앱이 사용하는 Fastify API입니다. 금액은 정수 KRW, ID와 작업 ID는 UUID, 시각은 시간대가 포함된 ISO 8601입니다. API는 JSON을 사용하며 기본 흐름에 LLM 호출이 없습니다. 상세 보완 계약은 [API_COMPLETION.md](API_COMPLETION.md)를 참고하세요.

## 인증과 링크

- `/api/admin/*`: `Authorization: Bearer <ADMIN_TOKEN>`. 토큰은 32자 이상의 무작위 비밀값으로 설정하고 공개 Git에 저장하지 않습니다.
- `/api/respond*`: 초대 토큰을 `Authorization: Bearer <token>`으로 보냅니다. URL은 `${PUBLIC_BASE_URL}/respond#<token>` 형태이며 fragment는 서버 요청/Referer에 포함되지 않습니다.
- 서버는 링크 토큰 원문 대신 SHA-256 해시를 저장하고, 발급 작업의 재시도 응답은 서버 암호화 키로 보호합니다. 응답은 no-store이며 로그에 URL·본문·인증 정보를 기록하지 않습니다.
- 링크 종류는 `common`, `personal`, `submission`입니다. `submission`은 공통 신청자가 자기 제출만 고치는 권한입니다. 공통/수정 링크는 개인 회비 요청을 읽거나 제출할 수 없습니다.
- `GET`은 응답 상태를 바꾸지 않습니다.

## 공통 응답 형태

```ts
type Rsvp = 'attending' | 'declined' | 'undecided';
type EventView = {
  id: string; clubName: string; title: string; startsAt: string | null;
  location: string; feeAmount: number; capacity: number | null;
  deadline: string | null; notice: string; cancelled: boolean; version: number;
  pollOptions: { id: string; label: string }[]; pollDeadline: string | null;
};
type SubmissionView = {
  id: string; eventId: string; name: string; rsvp: Rsvp;
  noticeAcknowledged: boolean; selectedOptions: string[]; version: number;
  eventVersion: number; updatedAt: string; verification: 'pending' | 'linked';
  source?: 'operator' | 'member';
};
type PaymentRequest = {
  id: string; amount: number; depositorName: string; depositedAt: string;
  status: 'pending' | 'confirmed' | 'rejected'; createdAt: string;
  confirmedAmount?: number;
};
```

회원에게 공개되는 `confirmedAmount`는 요청 상태가 확정됐을 때만 포함합니다. 운영자 전용 owner/charge/ledger ID와 검토 영수증은 공개 응답에 포함되지 않습니다. `source`는 운영자 수동 등록과 회원 제출을 구별합니다.

## 회원 경로

| Method / path | 동작 |
| --- | --- |
| `GET /api/respond` | 행사, 링크 권한/상태, 본인 응답과 허용된 개인 입금 요청을 반환합니다. 링크가 만료·취소된 경우 제한된 상태를 알려 줍니다. |
| `POST /api/respond` | 참석·안내 확인·투표를 저장합니다. 본문은 `{operationId,expectedVersion,eventVersion,name,rsvp,noticeAcknowledged,selectedOptions}`입니다. 공통 링크는 자기 응답만 수정하는 edit token을 응답에 포함합니다. |
| `POST /api/respond/payments` | 활성 개인 링크만 사용 가능합니다. `{operationId,eventVersion,amount,depositorName,depositedAt}`를 받아 입금 확인 요청을 저장합니다. 실제 납부 원장은 변경하지 않습니다. |

새 응답은 `expectedVersion: 0`으로 보냅니다. 같은 작업 ID와 같은 본문 재요청은 저장된 결과를 돌려주며, 같은 ID의 다른 본문은 충돌입니다. 공통 링크의 이름/전화 입력을 기존 회원과 자동 연결하지 않습니다. 참석 정원·행사 버전·투표 후보·마감과 링크 범위를 서버가 검사합니다.

## 운영자 경로

모든 `/api/admin/*` 경로는 운영자 인증을 요구합니다.

| Method / path | 동작 |
| --- | --- |
| `POST /api/admin/events` | 행사 생성/수정. `{operationId,expectedVersion,event}`. 최초 생성에만 선택 필드 `initialResponses`를 허용하며 행사와 최대 500개 수동 응답을 한 트랜잭션으로 저장합니다. |
| `POST /api/admin/events/:id/links` | 공통/개인 링크 발급. `{operationId,kind,memberId?,expiresAt,scopes?}`. 원문 토큰은 해당 발급 결과에서만 반환됩니다. |
| `POST /api/admin/events/:id/links/revoke` | `{operationId,issuedOperationId}`로 발급 작업을 지정해 해당 링크를 취소합니다. 공통 링크에서 파생된 자기 제출 수정 링크도 취소합니다. |
| `POST /api/admin/events/:id/links/revoke-member` | `{operationId,memberId}`로 이 행사에서 해당 회원의 개인 링크를 취소합니다. 응답은 `{revokedCount}`입니다. |
| `POST /api/admin/links/:id/revoke` | 링크 UUID로 취소합니다. 공통 링크이면 파생 수정 링크도 함께 취소합니다. |
| `GET /api/admin/events/:id/responses` | 행사 응답과 입금 요청의 운영자용 스냅샷을 반환합니다. 검토 영수증은 이 운영자 응답에만 제공됩니다. |
| `GET /api/admin/changes?cursor=N` | 변경 로그를 순서대로 최대 100건 반환합니다. 커서가 앞서 있으면 `CURSOR_RESET`을 반환하며 클라이언트가 전체 스냅샷과 ID upsert로 복구합니다. |
| `POST /api/admin/events/:id/submissions` | 게시 후 운영자 응답 제안을 생성합니다. `{operationId,id,memberId,expectedVersion:0,eventVersion,name,rsvp,noticeAcknowledged,selectedOptions}`. 기존 회원 응답이 있으면 덮어쓰지 않고 충돌을 돌려줍니다. |
| `POST /api/admin/events/:id/submissions/:submissionId` | 기준 응답 버전이 일치할 때 운영자 제안을 수정합니다. 충돌 응답에는 최신 서버 응답이 포함됩니다. |
| `POST /api/admin/events/:id/submissions/:submissionId/link` | `{operationId,expectedVersion,memberId}`로 운영자가 미확인 제출을 회원과 명시적으로 연결합니다. 이름으로 자동 연결하지 않습니다. |
| `POST /api/admin/events/:id/payments/:requestId/review/prepare` | `{operationId,reviewId,ledgerEntryId,chargeId,confirmedAmount,ownerId,reason}`로 단일 납부 검토와 원장 ID를 예약합니다. 예약 동안 회원 화면 상태는 대기입니다. |
| `POST /api/admin/events/:id/payments/:requestId/review/commit` | `{operationId,reviewId,ledgerEntryId}`로 로컬 원장 반영 뒤 서버 검토를 확정합니다. 재시도는 같은 검토 영수증을 반환합니다. |
| `POST /api/admin/events/:id/payments/:requestId/review` | `{operationId,status:'rejected',reason}`로 반려합니다. 확인은 prepare/commit 단계를 사용하며 이 경로의 직접 `confirmed`는 거부됩니다. |

서버는 회비 원장을 소유하지 않습니다. 확인 금액은 회원이 주장한 요청액과 별도로 운영자가 실제 입금을 확인해 입력합니다. Android는 원장 ID를 한 번 반영하고 검토 ID/owner를 대조해 재시도·복구합니다. 충돌을 자동으로 덮어쓰지 않습니다.

## 오류와 배포

오류 본문은 `{error:{code,message},current?}`입니다. 대표 상태는 잘못된 입력 400, 인증 401, 범위 403, 미발견 404, 만료 410, 버전/정원 충돌 409, 마감/취소 422, 제한 429, 저장 불확실 500입니다. 오류가 나면 회원 웹은 작업 ID와 입력을 유지하고 운영자 앱은 대기 작업을 재시도할 수 있습니다.

서버 실행 예시는 localhost 개발용입니다. 실제 링크를 공유하기 전에 외부에서 접근 가능한 HTTPS origin을 `PUBLIC_BASE_URL`과 Android 설정에 동일하게 지정해야 합니다. 현재 사용자가 지정한 도메인은 없으며 서버를 공개 배포하지 않았습니다. GitHub 저장소 공개는 운영 서비스 배포 권한을 의미하지 않습니다.
