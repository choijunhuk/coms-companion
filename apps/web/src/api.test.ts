import { test } from 'node:test';
import assert from 'node:assert/strict';
import { apiRequest } from './api.ts';

const error = (status: number, code: string) => Response.json({ error: { code, message: code } }, { status });

test('expired COMS access refreshes through the cookie route once and preserves the original submission', async () => {
  const calls: { url: string; init: RequestInit }[] = [];
  const replies = [error(401, 'COMS_LOGIN_REQUIRED'), new Response(null, { status: 204 }), Response.json({ saved: true })];
  const send = (async (url, init) => { calls.push({ url: String(url), init: init! }); return replies.shift()!; }) as typeof fetch;
  const payload = { operationId: 'existing-operation', identityMemberId: 'member-a', rsvp: 'attending' };
  assert.deepEqual(await apiRequest('/companion/', 'invite-a', '/api/respond', payload, send), { saved: true });
  assert.deepEqual(calls.map(call => call.url), ['/companion/api/respond', '/api/auth/refresh', '/companion/api/respond']);
  assert.equal(calls[0].init.body, calls[2].init.body);
  assert.deepEqual(JSON.parse(String(calls[2].init.body)), payload);
  assert.equal(calls[1].init.headers, undefined);
  assert.equal(calls[1].init.body, undefined);
  assert(calls.every(call => call.init.credentials === 'same-origin' && call.init.redirect === 'error'));
});

test('rejected refresh keeps the login prompt and does not replay a save', async () => {
  let calls = 0;
  const send = (async () => ++calls === 1 ? error(401, 'COMS_LOGIN_REQUIRED') : error(403, 'DENIED')) as typeof fetch;
  await assert.rejects(apiRequest('/companion/', 'invite', '/api/respond', {}, send), { status: 401, code: 'COMS_LOGIN_REQUIRED' });
  assert.equal(calls, 2);
});

test('eligibility and account changes never cause an authentication retry', async () => {
  for (const [status, code] of [[403, 'COMS_MEMBER_REQUIRED'], [409, 'COMS_ACCOUNT_CHANGED']] as const) {
    let calls = 0;
    const send = (async () => { calls++; return error(status, code); }) as typeof fetch;
    await assert.rejects(apiRequest('/companion/', 'invite', '/api/respond', {}, send), { status, code });
    assert.equal(calls, 1);
  }
});

test('unavailable refresh preserves an uncertain original operation instead of claiming logout', async () => {
  let calls = 0;
  const send = (async () => ++calls === 1 ? error(401, 'COMS_LOGIN_REQUIRED') : error(503, 'UNAVAILABLE')) as typeof fetch;
  await assert.rejects(apiRequest('/companion/', 'invite', '/api/respond', {}, send), { status: 503, code: 'COMS_AUTH_UNAVAILABLE' });
  assert.equal(calls, 2);
});

test('a second authentication failure stops after one refresh', async () => {
  let calls = 0;
  const send = (async () => ++calls === 2 ? new Response(null, { status: 204 }) : error(401, 'COMS_LOGIN_REQUIRED')) as typeof fetch;
  await assert.rejects(apiRequest('/companion/', 'invite', '/api/respond', undefined, send), { status: 401, code: 'COMS_LOGIN_REQUIRED' });
  assert.equal(calls, 3);
});
