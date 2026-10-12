import { useEffect, useRef, useState } from 'react';
import type { Draft, PaymentDraft, Rsvp, PendingOperation } from './draft';
import { keepOperation, nextOperation, paymentError, pendingKey, readPending, responseError } from './draft';

type EventView = {
  id: string; clubName: string; title: string; startsAt: string | null; location: string;
  feeAmount: number; capacity: number | null; deadline: string | null; notice: string;
  cancelled: boolean; version: number; pollOptions: { id: string; label: string }[]; pollDeadline: string | null;
};
type Submission = {
  id: string; name: string; rsvp: Rsvp; noticeAcknowledged: boolean;
  selectedOptions: string[]; version: number; eventVersion: number; updatedAt: string; verification: 'pending' | 'linked';
};
type PaymentRequest = { id: string; amount: number; depositorName: string; depositedAt: string; status: 'pending' | 'confirmed' | 'rejected'; createdAt: string };
type Context = {
  event: EventView;
  access: { kind: 'common' | 'personal' | 'submission'; state: 'open' | 'closed' | 'cancelled' | 'expired' | 'revoked'; scopes: ('rsvp' | 'notice' | 'vote' | 'payment')[]; canPay: boolean; message: string };
  submission: Submission | null; paymentRequests: PaymentRequest[];
};
type Step = 'detail' | 'edit' | 'review' | 'done' | 'payment' | 'paymentReview' | 'paymentDone';
type ApiError = Error & { code?: string; status?: number };

const initialDraft: Draft = { name: '', rsvp: 'undecided', noticeAcknowledged: false, selectedOptions: [] };
const initialPayment: PaymentDraft = { amount: '', depositorName: '', depositedAt: '' };
const rsvpLabels: Record<Rsvp, string> = { attending: '참석', declined: '불참', undecided: '미정' };
const stateLabels: Record<Context['access']['state'], string> = {
  open: '', closed: '응답이 마감됐어요.', cancelled: '취소된 행사예요.',
  expired: '링크 유효기간이 지났어요.', revoked: '사용할 수 없는 링크예요.',
};

function displayDate(value: string | null): string {
  if (!value) return '미정';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short',
  }).format(date);
}

function App() {
  const [token, setToken] = useState(() => location.hash.slice(1));
  const [context, setContext] = useState<Context | null>(null);
  const [loading, setLoading] = useState(true);
  const [step, setStep] = useState<Step>('detail');
  const [draft, setDraft] = useState<Draft>(initialDraft);
  const [payment, setPayment] = useState<PaymentDraft>(initialPayment);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [uncertainResponse, setUncertainResponse] = useState(false);
  const [uncertainPayment, setUncertainPayment] = useState(false);
  const [success, setSuccess] = useState<Submission | PaymentRequest | null>(null);
  const responseOperation = useRef<string | null>(null);
  const paymentOperation = useRef<string | null>(null);
  const responsePayload = useRef<Record<string, unknown> | null>(null);
  const paymentPayload = useRef<Record<string, unknown> | null>(null);
  const storageKey = useRef<string | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  async function request<T>(path: string, payload?: object): Promise<T> {
    const response = await fetch(path, {
      method: payload ? 'POST' : 'GET',
      headers: { Authorization: `Bearer ${token}`, ...(payload ? { 'Content-Type': 'application/json' } : {}) },
      body: payload ? JSON.stringify(payload) : undefined,
      cache: 'no-store',
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) {
      const failure = new Error(result.error?.message || '요청을 처리하지 못했어요.') as ApiError;
      failure.code = result.error?.code;
      failure.status = response.status;
      throw failure;
    }
    return result as T;
  }

  async function refresh(preserveDraft = false) {
    if (!token) { setError('응답 링크가 없어요. 운영자에게 새 링크를 요청해 주세요.'); setLoading(false); return; }
    setLoading(true);
    try {
      const next = await request<Context>('/api/respond');
      setContext(next);
      if (!preserveDraft) setDraft(next.submission ? {
        name: next.submission.name, rsvp: next.submission.rsvp,
        noticeAcknowledged: (next.submission.eventVersion === next.event.version || !next.access.scopes.includes('notice')) && next.submission.noticeAcknowledged,
        selectedOptions: next.submission.selectedOptions,
      } : { ...initialDraft });
      else if (next.submission && next.submission.eventVersion !== next.event.version && next.access.scopes.includes('notice') && !responsePayload.current) {
        setDraft((previous) => ({ ...previous, noticeAcknowledged: false }));
      }
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '연결할 수 없어요. 다시 시도해 주세요.');
    } finally { setLoading(false); }
  }

  useEffect(() => {
    let cancelled = false;
    storageKey.current = null;
    async function initialize() {
      let pending: PendingOperation | null = null;
      try {
        const key = await pendingKey(token);
        if (cancelled) return;
        storageKey.current = key;
        pending = readPending(sessionStorage, key);
      } catch { /* Session storage may be unavailable; in-memory retry remains active. */ }
      if (cancelled) return;
      if (pending?.kind === 'response') {
        responsePayload.current = pending.payload;
        responseOperation.current = pending.payload.operationId as string;
        setDraft(pending.draft);
        setUncertainResponse(true);
        setStep('review');
      } else if (pending?.kind === 'payment') {
        paymentPayload.current = pending.payload;
        paymentOperation.current = pending.payload.operationId as string;
        setPayment(pending.draft);
        setUncertainPayment(true);
        setStep('paymentReview');
      }
      void refresh(!!pending);
    }
    void initialize();
    return () => { cancelled = true; };
  }, [token]);
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);

  function savePending(pending: PendingOperation) {
    try { if (storageKey.current) sessionStorage.setItem(storageKey.current, JSON.stringify(pending)); } catch { /* In-memory retry still works. */ }
  }
  function clearPending() {
    try { if (storageKey.current) sessionStorage.removeItem(storageKey.current); } catch { /* In-memory state is cleared below. */ }
  }

  function changeDraft(next: Draft) { if (uncertainResponse) return; setDraft(next); responseOperation.current = null; responsePayload.current = null; setError(''); }
  function changePayment(next: PaymentDraft) { if (uncertainPayment) return; setPayment(next); paymentOperation.current = null; paymentPayload.current = null; setError(''); }

  async function submitResponse() {
    if (saving || (!context && !responsePayload.current)) return;
    if (!responsePayload.current) {
      if (!context) return;
      const invalid = responseError(draft, context.event.pollOptions.map((option) => option.id), context.submission?.selectedOptions, !!context.submission && context.submission.eventVersion !== context.event.version && context.access.scopes.includes('notice'));
      if (invalid) { setError(invalid); setStep('edit'); return; }
      responseOperation.current = nextOperation(responseOperation.current, false);
      responsePayload.current = {
        operationId: responseOperation.current, expectedVersion: context.submission?.version ?? 0,
        eventVersion: context.event.version, name: draft.name.trim(), rsvp: draft.rsvp,
        noticeAcknowledged: draft.noticeAcknowledged, selectedOptions: draft.selectedOptions,
      };
    }
    savePending({ kind: 'response', payload: responsePayload.current, draft });
    setSaving(true); setError('');
    try {
      const result = await request<{ submission: Submission; editToken?: string }>('/api/respond', responsePayload.current!);
      setSuccess(result.submission);
      if (context) setContext({ ...context, submission: result.submission });
      responseOperation.current = null;
      responsePayload.current = null;
      setUncertainResponse(false);
      clearPending();
      if (result.editToken) {
        history.replaceState(null, '', `/respond#${encodeURIComponent(result.editToken)}`);
        setToken(result.editToken);
      } else if (!context) void refresh(true);
      setStep('done');
    } catch (cause) {
      const failure = cause as ApiError;
      const uncertain = keepOperation(failure.status, uncertainResponse);
      setUncertainResponse(uncertain);
      if (!uncertain) { responseOperation.current = null; responsePayload.current = null; clearPending(); }
      setError(failure.code === 'CONFLICT' || failure.code === 'EVENT_CONFLICT'
        ? '다른 변경이 먼저 저장됐어요. 최신 내용을 다시 읽고 입력한 내용을 확인해 주세요.'
        : uncertain ? '저장 결과를 확인하지 못했어요. 원래 요청으로 다시 확인할 때까지 입력을 바꿀 수 없어요.' : `${failure.message} 서버가 이 요청을 저장하지 않았어요. 내용을 수정할 수 있어요.`);
    } finally { setSaving(false); }
  }

  async function submitPayment() {
    if (saving || (!context && !paymentPayload.current)) return;
    if (!paymentPayload.current) {
      if (!context) return;
      const invalid = paymentError(payment);
      if (invalid) { setError(invalid); setStep('payment'); return; }
      paymentOperation.current = nextOperation(paymentOperation.current, false);
      paymentPayload.current = {
        operationId: paymentOperation.current, eventVersion: context.event.version,
        amount: Number(payment.amount), depositorName: payment.depositorName.trim(),
        depositedAt: new Date(payment.depositedAt).toISOString(),
      };
    }
    savePending({ kind: 'payment', payload: paymentPayload.current, draft: payment });
    setSaving(true); setError('');
    try {
      const result = await request<{ request: PaymentRequest }>('/api/respond/payments', paymentPayload.current!);
      setSuccess(result.request);
      if (context) setContext({ ...context, paymentRequests: [result.request, ...context.paymentRequests] });
      else void refresh(true);
      paymentOperation.current = null;
      paymentPayload.current = null;
      setUncertainPayment(false);
      clearPending();
      setStep('paymentDone');
    } catch (cause) {
      const failure = cause as ApiError;
      const uncertain = keepOperation(failure.status, uncertainPayment);
      setUncertainPayment(uncertain);
      if (!uncertain) { paymentOperation.current = null; paymentPayload.current = null; clearPending(); }
      setError(failure.code === 'EVENT_CONFLICT'
        ? '행사 정보가 바뀌었어요. 최신 내용을 확인한 뒤 다시 제출해 주세요.'
        : uncertain ? '저장 결과를 확인하지 못했어요. 원래 요청으로 다시 확인할 때까지 입력을 바꿀 수 없어요.' : `${failure.message} 서버가 이 요청을 저장하지 않았어요. 내용을 수정할 수 있어요.`);
    } finally { setSaving(false); }
  }

  const event = context?.event;
  const access = context?.access;
  const open = access?.state === 'open';
  const staleResponse = !!context?.submission && context.submission.eventVersion !== event?.version;
  const canRespond = access?.scopes.some((scope) => scope === 'rsvp' || scope === 'notice' || scope === 'vote') ?? false;
  const pollOpen = open && (!event?.pollDeadline || new Date(event.pollDeadline).getTime() > Date.now());
  const canEditResponse = access?.scopes.includes('rsvp') || access?.scopes.includes('notice') || (access?.scopes.includes('vote') && (!!event?.pollOptions.length || !!context?.submission?.selectedOptions.length) && pollOpen);
  const amount = new Intl.NumberFormat('ko-KR');
  const optionLabel = (id: string) => event?.pollOptions.find((option) => option.id === id)?.label ?? '삭제된 이전 후보';
  const optionLabels = (ids: string[]) => ids.map(optionLabel).join(', ') || '선택 없음';

  return <main className="shell">
    <header className="brand">COM’s Companion <span>회원 응답</span></header>
    {loading && !context ? <section className="card" role="status">행사 정보를 불러오는 중이에요…</section> : null}
    {error ? <div className="alert" role="alert" tabIndex={-1} ref={errorRef}>⚠ {error}
      <button type="button" className="text-button" onClick={() => void refresh(!!context || uncertainResponse || uncertainPayment)}>다시 읽기</button>
    </div> : null}
    {!context && !loading ? <button type="button" onClick={() => void refresh(uncertainResponse || uncertainPayment)}>다시 시도</button> : null}
    {uncertainResponse && <section className="card" role="status"><h2>응답 저장 결과 미확인</h2><p>입력은 그대로 보관했어요. 원래 요청으로 다시 확인한 뒤에 수정할 수 있어요.</p><button type="button" disabled={saving} onClick={() => void submitResponse()}>{saving ? '확인 중…' : '원래 응답 다시 확인'}</button></section>}
    {uncertainPayment && <section className="card" role="status"><h2>입금 요청 저장 결과 미확인</h2><p>입력은 그대로 보관했어요. 원래 요청으로 다시 확인한 뒤에 수정할 수 있어요.</p><button type="button" disabled={saving} onClick={() => void submitPayment()}>{saving ? '확인 중…' : '원래 입금 요청 다시 확인'}</button></section>}
    {!event && step === 'done' && success && 'rsvp' in success && <section className="card" role="status"><h2>응답을 저장했어요</h2><p>저장 시각 {displayDate(success.updatedAt)}</p></section>}
    {!event && step === 'paymentDone' && success && 'amount' in success && <section className="card" role="status"><h2>입금 확인을 요청했어요</h2><p>{amount.format(success.amount)}원 · 운영자 확인 대기</p></section>}
    {event && <>
      <p className="eyebrow">{event.clubName}</p>
      <h1>{event.title}</h1>
      <section className="card facts" aria-label="행사 정보">
        <div><span>일시</span><strong>{displayDate(event.startsAt)}</strong></div>
        <div><span>장소</span><strong>{event.location || '미정'}</strong></div>
        <div><span>참가비</span><strong>{amount.format(event.feeAmount)}원</strong></div>
        <div><span>응답 마감</span><strong>{displayDate(event.deadline)}</strong></div>
        {event.capacity !== null && <div><span>정원</span><strong>{event.capacity}명</strong></div>}
      </section>
      {event.notice && <section className="card"><h2>안내사항</h2><p className="preline">{event.notice}</p></section>}
      {staleResponse && <div className="alert" role="status">ℹ 행사 안내가 변경됐어요. 이전 안내에 대한 응답이므로 최신 내용을 확인하고 다시 제출해 주세요.</div>}
      {access && !open && <div className="alert" role="status">ℹ {access.message || stateLabels[access.state]} {access.state === 'closed' && context?.submission ? '저장된 응답은 아래에서 확인할 수 있어요.' : ''}</div>}
      {open && access?.scopes.includes('vote') && !pollOpen && <div className="alert" role="status">ℹ 일정 투표가 마감됐어요. 저장된 선택은 확인할 수 있어요.</div>}
      {step === 'detail' && <>
        {canRespond && context?.submission && <section className="card"><h2>저장된 응답</h2>{access?.scopes.includes('rsvp') && <p>참석: {rsvpLabels[context.submission.rsvp]}</p>}{access?.scopes.includes('notice') && <p>안내: {context.submission.noticeAcknowledged ? '확인함' : '미확인'}</p>}{access?.scopes.includes('vote') && <p>선택한 일정: {optionLabels(context.submission.selectedOptions)}</p>}<p className="muted">{context.submission.verification === 'pending' ? '운영자 확인 대기' : '회원 연결됨'} · {displayDate(context.submission.updatedAt)}</p></section>}
        {access?.kind === 'personal' && context?.paymentRequests.map((item) => <section className="card" key={item.id}><h2>입금 확인 요청</h2><p>{amount.format(item.amount)}원 · {item.status === 'pending' ? '운영자 확인 대기' : item.status === 'confirmed' ? '운영자가 확인함' : '운영자가 반려함'}</p></section>)}
        {open && <div className="actions">{canEditResponse && <button type="button" onClick={() => setStep('edit')}>{context?.submission ? '응답 수정' : '응답하기'}</button>}{access?.kind === 'personal' && access.canPay && <button type="button" className="secondary" onClick={() => setStep('payment')}>입금 확인 요청</button>}</div>}
      </>}
      {(step === 'edit' || step === 'review') && open && !uncertainResponse && <>
        <p className="step">{step === 'edit' ? '1 · 응답 입력' : '2 · 최종 확인'}</p>
        {step === 'edit' ? <section className="card form">
          <h2>응답 입력</h2>
          <label>이름 <input value={draft.name} maxLength={100} autoComplete="name" onChange={(e) => changeDraft({ ...draft, name: e.target.value })} required /></label>
          {access?.scopes.includes('rsvp') && <fieldset><legend>참석 여부</legend>{(['attending', 'declined', 'undecided'] as const).map((value) => <label className="choice" key={value}><input type="radio" name="rsvp" checked={draft.rsvp === value} onChange={() => changeDraft({ ...draft, rsvp: value })} />{rsvpLabels[value]}</label>)}</fieldset>}
          {access?.scopes.includes('notice') && <label className="choice"><input type="checkbox" checked={draft.noticeAcknowledged} onChange={(e) => changeDraft({ ...draft, noticeAcknowledged: e.target.checked })} />안내사항을 확인했어요</label>}
          {access?.scopes.includes('vote') && (event.pollOptions.length > 0 || draft.selectedOptions.length > 0) && <fieldset disabled={!pollOpen}><legend>가능한 일정 (복수 선택)</legend>{event.pollOptions.map((option) => <label className="choice" key={option.id}><input type="checkbox" checked={draft.selectedOptions.includes(option.id)} onChange={(e) => changeDraft({ ...draft, selectedOptions: e.target.checked ? [...draft.selectedOptions, option.id] : draft.selectedOptions.filter((id) => id !== option.id) })} />{option.label}</label>)}{draft.selectedOptions.filter((id) => !event.pollOptions.some((option) => option.id === id)).map((id) => <label className="choice" key={id}><input type="checkbox" checked onChange={() => changeDraft({ ...draft, selectedOptions: draft.selectedOptions.filter((choice) => choice !== id) })} />삭제된 이전 후보</label>)}{!pollOpen && <p className="muted">투표가 종료되어 기존 선택을 유지해요.</p>}</fieldset>}
          <button type="button" onClick={() => { const invalid = responseError(draft, event.pollOptions.map((option) => option.id), context?.submission?.selectedOptions, staleResponse && !!access?.scopes.includes('notice')); invalid ? setError(invalid) : (setError(''), setStep('review')); }}>입력 내용 확인</button>
        </section> : <section className="card"><h2>제출할 내용</h2><p>이름: {draft.name}</p>{access?.scopes.includes('rsvp') && <p>참석: {rsvpLabels[draft.rsvp]}</p>}{access?.scopes.includes('notice') && <p>안내: {draft.noticeAcknowledged ? '확인함' : '확인하지 않음'}</p>}{access?.scopes.includes('vote') && (event.pollOptions.length > 0 || draft.selectedOptions.length > 0) && <p>가능 일정: {optionLabels(draft.selectedOptions)}</p>}<p className="muted">{access?.kind === 'common' ? '운영자가 확인할 때까지 미확인 응답으로 저장돼요.' : '제출 후 저장 결과를 확인해 주세요.'}</p><div className="actions"><button type="button" disabled={saving} onClick={() => void submitResponse()}>{saving ? '저장 중…' : '응답 제출'}</button><button type="button" className="secondary" disabled={saving} onClick={() => setStep('edit')}>수정</button></div></section>}
      </>}
      {step === 'done' && success && 'rsvp' in success && <section className="card" role="status"><h2>응답을 저장했어요</h2>{access?.scopes.includes('rsvp') && <p>{rsvpLabels[success.rsvp]}</p>}{access?.scopes.includes('notice') && <p>{success.noticeAcknowledged ? '안내 확인함' : '안내 미확인'}</p>}{access?.scopes.includes('vote') && (event.pollOptions.length > 0 || success.selectedOptions.length > 0) && <p>선택한 일정: {optionLabels(success.selectedOptions)}</p>}<p>{success.verification === 'pending' ? '운영자 확인 대기' : '회원 연결됨'}</p><p className="muted">저장 시각 {displayDate(success.updatedAt)}</p>{access?.scopes.includes('vote') && event.pollDeadline && <p className="muted">투표 수정 기한 {displayDate(event.pollDeadline)}</p>}{open && <p className="muted">마감 전 이 주소로 다시 열어 수정할 수 있어요. 링크를 다른 사람에게 보내지 마세요.</p>}{open && canEditResponse && <button type="button" onClick={() => setStep('edit')}>응답 수정</button>}</section>}
      {(step === 'payment' || step === 'paymentReview') && open && access?.canPay && !uncertainPayment && <>
        <p className="step">{step === 'payment' ? '1 · 입금 내용 입력' : '2 · 입금 확인 요청 검토'}</p>
        {step === 'payment' ? <section className="card form"><h2>입금 확인 요청</h2><p className="muted">운영자가 실제 입금 내역을 확인한 뒤 납부를 확정해요.</p><label>금액 (원)<input type="number" min="1" step="1" inputMode="numeric" value={payment.amount} onChange={(e) => changePayment({ ...payment, amount: e.target.value })} /></label><label>입금자명<input maxLength={100} value={payment.depositorName} onChange={(e) => changePayment({ ...payment, depositorName: e.target.value })} /></label><label>입금 시각 (기기 시간)<input type="datetime-local" value={payment.depositedAt} onChange={(e) => changePayment({ ...payment, depositedAt: e.target.value })} /></label><button type="button" onClick={() => { const invalid = paymentError(payment); invalid ? setError(invalid) : (setError(''), setStep('paymentReview')); }}>입력 내용 확인</button></section>
          : <section className="card"><h2>요청할 내용</h2><p>금액: {amount.format(Number(payment.amount))}원</p><p>입금자명: {payment.depositorName}</p><p>입금 시각: {displayDate(new Date(payment.depositedAt).toISOString())}</p><p className="muted">이 요청만으로 납부 완료가 되지 않아요.</p><div className="actions"><button type="button" disabled={saving} onClick={() => void submitPayment()}>{saving ? '저장 중…' : '입금 확인 요청 제출'}</button><button type="button" className="secondary" disabled={saving} onClick={() => setStep('payment')}>수정</button></div></section>}
      </>}
      {step === 'paymentDone' && success && 'amount' in success && <section className="card" role="status"><h2>입금 확인을 요청했어요</h2><p>{amount.format(success.amount)}원 · 운영자 확인 대기</p><p className="muted">요청 시각 {displayDate(success.createdAt)}</p><button type="button" onClick={() => setStep('detail')}>행사 보기</button></section>}
      {step !== 'detail' && step !== 'done' && step !== 'paymentDone' && !uncertainResponse && !uncertainPayment && <button type="button" className="back" disabled={saving} onClick={() => { setError(''); setStep('detail'); }}>행사 정보로 돌아가기</button>}
    </>}
    <footer>COM’s Companion · 제출은 저장 확인 후 완료됩니다.</footer>
  </main>;
}

export default App;
