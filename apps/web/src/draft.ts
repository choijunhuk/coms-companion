export type Rsvp = 'attending' | 'declined' | 'undecided';
export type Draft = { name: string; rsvp: Rsvp; noticeAcknowledged: boolean; selectedOptions: string[] };
export type PaymentDraft = { amount: string; depositorName: string; depositedAt: string };

export function nextOperation(previous: string | null, changed: boolean): string {
  return changed || !previous ? crypto.randomUUID() : previous;
}

export function keepOperation(status?: number): boolean {
  return status === undefined || status >= 500;
}

export function responseError(draft: Draft, validOptions: string[], savedOptions: string[] = []): string | null {
  if (!draft.name.trim()) return '이름을 입력해 주세요.';
  if (draft.name.trim().length > 100) return '이름은 100자 이내로 입력해 주세요.';
  if (draft.selectedOptions.some((id) => !validOptions.includes(id) && !savedOptions.includes(id))) return '선택한 날짜를 다시 확인해 주세요.';
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
