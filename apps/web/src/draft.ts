export type Rsvp = 'attending' | 'declined' | 'undecided';
export type Draft = { name: string; rsvp: Rsvp; noticeAcknowledged: boolean; selectedOptions: string[] };
export type PaymentDraft = { amount: string; depositorName: string; depositedAt: string };

export function nextOperation(previous: string | null, changed: boolean): string {
  return changed || !previous ? crypto.randomUUID() : previous;
}

export function keepOperation(status?: number, previouslyUncertain = false): boolean {
  return previouslyUncertain || status === undefined || status === 408 || status === 429 || status >= 500;
}

export type PendingOperation =
  | { kind: 'response'; payload: Record<string, unknown>; draft: Draft }
  | { kind: 'payment'; payload: Record<string, unknown>; draft: PaymentDraft };

export async function pendingKey(token: string, memberId = ''): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(memberId ? `${token}\0coms:${memberId}` : token));
  return `coms-pending:${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')}`;
}

export function comsAuthUrl(page: 'login' | 'signup', responsePath: string, token: string): string {
  return `/${page}?returnTo=${encodeURIComponent(responsePath)}#${encodeURIComponent(token)}`;
}

export function readPending(storage: Pick<Storage, 'getItem'>, key: string): PendingOperation | null {
  try {
    const parsed: unknown = JSON.parse(storage.getItem(key) || 'null');
    if (!parsed || typeof parsed !== 'object' || !('kind' in parsed) || !('payload' in parsed) || !('draft' in parsed)) return null;
    const value = parsed as PendingOperation;
    if (!['response', 'payment'].includes(value.kind) || !value.payload || typeof value.payload !== 'object' || typeof value.payload.operationId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.payload.operationId) || !value.draft || typeof value.draft !== 'object') return null;
    if (value.kind === 'response' && (typeof value.draft.name !== 'string' || !Array.isArray(value.draft.selectedOptions))) return null;
    if (value.kind === 'payment' && (typeof value.draft.amount !== 'string' || typeof value.draft.depositorName !== 'string' || typeof value.draft.depositedAt !== 'string')) return null;
    return value;
  } catch { return null; }
}

export function responseError(draft: Draft, validOptions: string[], savedOptions: string[] = [], requireNoticeAcknowledgement = false): string | null {
  if (!draft.name.trim()) return '이름을 입력해 주세요.';
  if (draft.name.trim().length > 100) return '이름은 100자 이내로 입력해 주세요.';
  if (requireNoticeAcknowledgement && !draft.noticeAcknowledged) return '변경된 안내사항을 확인해 주세요.';
  const unchanged = [...draft.selectedOptions].sort().join('\0') === [...savedOptions].sort().join('\0');
  if (!unchanged && draft.selectedOptions.some((id) => !validOptions.includes(id))) return '선택한 날짜를 다시 확인해 주세요.';
  return null;
}

export function paymentError(draft: PaymentDraft): string | null {
  const amount = Number(draft.amount);
  if (!Number.isSafeInteger(amount) || amount <= 0) return '입금 금액을 1원 이상 입력해 주세요.';
  if (!draft.depositorName.trim() || draft.depositorName.trim().length > 100) return '입금자명을 입력해 주세요.';
  if (!draft.depositedAt || !Number.isFinite(Date.parse(draft.depositedAt))) return '입금 시각을 입력해 주세요.';
  if (Date.parse(draft.depositedAt) > Date.now()) return '미래 시각은 입력할 수 없어요.';
  return null;
}
