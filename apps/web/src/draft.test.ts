import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextOperation, responseError, paymentError, keepOperation } from './draft.ts';

test('uncertain retry reuses operation, changed draft gets a new one', () => {
  const first = nextOperation(null, false);
  assert.equal(nextOperation(first, false), first);
  assert.notEqual(nextOperation(first, true), first);
});

test('response requires a name and checks poll option IDs', () => {
  assert.equal(responseError({ name: '', rsvp: 'attending', noticeAcknowledged: false, selectedOptions: [] }, []), '이름을 입력해 주세요.');
  assert.equal(responseError({ name: '가상 회원', rsvp: 'attending', noticeAcknowledged: false, selectedOptions: ['unknown'] }, ['valid']), '선택한 날짜를 다시 확인해 주세요.');
});

test('deleted poll choice remains valid only when it was already saved', () => {
  const draft = { name: '가상 회원', rsvp: 'declined' as const, noticeAcknowledged: true, selectedOptions: ['removed'] };
  assert.equal(responseError(draft, [], ['removed']), null);
  assert.match(responseError(draft, [], []) ?? '', /날짜/);
});

test('uncertain transport and server failures retain the original operation', () => {
  assert.equal(keepOperation(undefined), true);
  assert.equal(keepOperation(500), true);
  assert.equal(keepOperation(503), true);
  assert.equal(keepOperation(409), false);
  assert.equal(keepOperation(422), false);
});

test('payment claim rejects invalid amount and future time', () => {
  assert.match(paymentError({ amount: '0', depositorName: '가상', depositedAt: '2026-01-01T12:00' }) ?? '', /금액/);
  assert.match(paymentError({ amount: '1000', depositorName: '가상', depositedAt: '2999-01-01T12:00' }) ?? '', /미래/);
});
