# Response API v1

All amounts are integer KRW; timestamps are ISO 8601 with explicit timezone (UTC on output). IDs are UUIDs; phones are not server identifiers. Event planning/fees stay on Android. `GET` never records RSVP/read/attendance. APIs use JSON and no LLM.

## Authentication and links
Operator: `Authorization: Bearer <ADMIN_TOKEN>` on `/api/admin/*`. Configure a random token of at least 32 chars outside Git. Member: opaque 32-byte random token in `Authorization: Bearer <link token>` on `/api/respond*`. Store only SHA-256 link hashes in SQLite. Browser URLs are `${PUBLIC_BASE_URL}/respond#<token>`; fragment is not sent in HTTP referrers. Do not copy personal/edit links into group messages. `/api/respond` has `Cache-Control: no-store`; server logs omit request URLs, bodies and authorization. Link kinds: `common`, `personal`, `submission` (own-submission edit only). Personal links have RSVP/notice/vote/payment scopes; common/edit links never read member ledgers or submit payments.

## Shared types
```ts
type Rsvp = 'attending' | 'declined' | 'undecided';
type EventView = {
  id: string; clubName: string; title: string; startsAt: string | null;
  location: string; feeAmount: number; capacity: number | null;
  deadline: string | null; notice: string; cancelled: boolean; version: number;
  pollOptions: {id: string; label: string}[]; pollDeadline: string | null;
};
type SubmissionView = {
  id: string; eventId: string; name: string; rsvp: Rsvp;
  noticeAcknowledged: boolean; selectedOptions: string[]; version: number;
  eventVersion: number; updatedAt: string; verification: 'pending' | 'linked';
};
type PaymentRequest = {
  id: string; amount: number; depositorName: string; depositedAt: string;
  status: 'pending' | 'confirmed' | 'rejected'; createdAt: string;
};
type ResponseContext = {
  event: EventView;
  access: {kind: 'common' | 'personal' | 'submission'; state: 'open' | 'closed' | 'cancelled' | 'expired' | 'revoked'; canPay: boolean; message: string};
  submission: SubmissionView | null; paymentRequests: PaymentRequest[];
};
```

## Member endpoints
- `GET /api/respond`: ResponseContext. No submission writes. An unknown token returns 404, expired/revoked known token returns context with restricted state. `canPay` only for a live personal link. Closed context retains own submitted response for read-only display.
- `POST /api/respond`: `{operationId: UUID, expectedVersion: number, eventVersion: number, name: string, rsvp: Rsvp, noticeAcknowledged: boolean, selectedOptions: string[]}`. expectedVersion=0 for creation. Returns `{submission: SubmissionView, editToken?: string}`. Common creation issues an edit token scoped to this one submission; personal link directly targets the one member. Same operation replay returns exactly the saved result; different payload with same operationId returns conflict. New token/response stored together transactionally. Common names are claims and never match/overwrite existing members automatically.
- `POST /api/respond/payments`: personal only. `{operationId: UUID, eventVersion: number, amount: number, depositorName: string, depositedAt: string}`. Returns `{request: PaymentRequest}`. Saves a claim only; cannot update any fee ledger. Retry uses the same operationId.

Errors: `{error: {code: string, message: string}, current?: SubmissionView}`. HTTP 400 invalid data, 401 missing auth, 403 scope/revoked, 404 unknown, 409 version conflict/capacity/idempotency mismatch, 410 expired, 422 closed/cancelled/poll closed, 429 limited, 500 save failure. Concrete codes: `INVALID_INPUT`, `INVALID_LINK`, `EXPIRED`, `REVOKED`, `CLOSED`, `CANCELLED`, `POLL_CLOSED`, `CAPACITY`, `CONFLICT`, `EVENT_CONFLICT`, `OPERATION_CONFLICT`, `SCOPE`, `RATE_LIMITED`, `SAVE_FAILED`. On uncertain network failure preserve operationId and draft; only server acceptance produces completion.

## Operator endpoints
- `POST /api/admin/events`: `{operationId, expectedVersion, event: EventView}`. Creation expectedVersion=0; version field reflects base version. Returns `{event}`. Explicit event validation, optimistic versioning. Updates preserve response eventVersion.
- `POST /api/admin/events/:id/links`: `{operationId, kind:'common'|'personal', memberId?:UUID, expiresAt:string, scopes?:('rsvp'|'notice'|'vote'|'payment')[]}`. Returns `{linkId, token, url}` once/replay for this operation. Defaults personal to all scopes; common permits rsvp/notice/vote only. Server stores token hash, and encrypts short-lived replay result using SERVER_ENCRYPTION_KEY.
- `POST /api/admin/links/:id/revoke`: `{operationId}`. Returns `{revoked:true}`.
- `GET /api/admin/events/:id/responses`: `{submissions: (SubmissionView & {memberId:string|null})[], paymentRequests:(PaymentRequest & {memberId:string})[], event:EventView}`. Authenticated, no-store. Full snapshot sync is safe to repeat.
- `POST /api/admin/events/:id/submissions/:submissionId/link`: `{operationId, expectedVersion, memberId:UUID}`. Explicit review only; conflict if member already has response. Returns `{submission}`. Never a name-based merge.
- `POST /api/admin/events/:id/submissions/:submissionId`: `{operationId, expectedVersion, eventVersion, name, rsvp, noticeAcknowledged, selectedOptions}`. Operator proposal uses current server base version; conflict returns latest response for human review.
- `POST /api/admin/events/:id/payments/:requestId/review`: `{operationId, status:'confirmed'|'rejected', ledgerEntryId?:UUID}`. Confirmation requires a ledgerEntryId from Android's independent actual-payment record. Repeated confirmation is idempotent; no server fee write.

## Deployment boundary
Local development only until a server/HTTPS host is selected. Set PUBLIC_BASE_URL to a real reachable base before sharing links. Android rejects non-HTTPS production URLs; emulator local HTTP may be enabled only in debug. Public repository creation does not authorize deployment to existing COM’s services.
