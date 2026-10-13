import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { buildApp } from '../apps/server/src/app.ts';

const chrome = process.env.CHROME_BIN || ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/opt/google/chrome/chrome'].find(existsSync);
assert.ok(chrome, 'Chrome is required; set CHROME_BIN to its executable');
const temporary = mkdtempSync(join(tmpdir(), 'companion-browser-'));
const profile = join(temporary, 'chrome');
const artifacts = resolve('artifacts');
mkdirSync(profile);
mkdirSync(artifacts, { recursive: true });
const adminToken = randomBytes(48).toString('hex');
const encryptionKey = randomBytes(32).toString('hex');
const auth = { authorization: `Bearer ${adminToken}` };
const eventId = randomUUID();
let app;
let browser;
let socket;

const bounded = (promise, label, ms = 10000) => Promise.race([
  promise,
  delay(ms).then(() => { throw new Error(`Timed out: ${label}`); }),
]);

async function availablePort() {
  const server = createServer();
  await new Promise((done, fail) => server.once('error', fail).listen(0, '127.0.0.1', done));
  const port = server.address().port;
  await new Promise((done) => server.close(done));
  return port;
}

async function waitFor(check, label, ms = 10000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const result = await check();
    if (result) return result;
    await delay(100);
  }
  throw new Error(`Timed out: ${label}`);
}

async function admin(method, url, payload) {
  const reply = await app.inject({ method, url, headers: auth, ...(payload ? { payload } : {}) });
  assert.equal(reply.statusCode, 200, `${url}: ${reply.body}`);
  return reply.json();
}

async function link(kind) {
  const result = await admin('POST', `/api/admin/events/${eventId}/links`, {
    operationId: randomUUID(), kind,
    ...(kind === 'personal' ? { memberId: randomUUID() } : {}),
    expiresAt: '2030-01-01T00:00:00Z',
  });
  return result.token;
}

function cdp(ws) {
  let id = 0;
  const pending = new Map();
  const listeners = new Map();
  ws.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data);
    if (message.id) {
      const waiter = pending.get(message.id);
      pending.delete(message.id);
      if (waiter) message.error ? waiter.reject(new Error(JSON.stringify(message.error))) : waiter.resolve(message.result);
    } else if (message.method) listeners.get(message.method)?.(message.params);
  });
  return {
    send(method, params = {}) {
      const requestId = ++id;
      return bounded(new Promise((resolve, reject) => {
        pending.set(requestId, { resolve, reject });
        ws.send(JSON.stringify({ id: requestId, method, params }));
      }), method);
    },
    on(method, listener) { listeners.set(method, listener); },
    off(method) { listeners.delete(method); },
  };
}

async function evaluate(session, expression) {
  const result = await session.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(`Browser JS failed: ${JSON.stringify(result.exceptionDetails)}`);
  return result.result.value;
}

async function click(session, label) {
  assert.equal(await evaluate(session, `(() => { const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === ${JSON.stringify(label)}); if (!b || b.disabled) return false; b.click(); return true; })()`), true, `button: ${label}`);
}

async function fill(session, label, value) {
  assert.equal(await evaluate(session, `(() => { const input = [...document.querySelectorAll('label')].find(x => x.textContent.includes(${JSON.stringify(label)}))?.querySelector('input'); if (!input) return false; Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, ${JSON.stringify(value)}); input.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`), true, `input: ${label}`);
}

async function choose(session, label) {
  assert.equal(await evaluate(session, `(() => { const input = [...document.querySelectorAll('label.choice')].find(x => x.textContent.trim() === ${JSON.stringify(label)})?.querySelector('input'); if (!input) return false; input.click(); return true; })()`), true, `choice: ${label}`);
}

async function visible(session, text) {
  return waitFor(() => evaluate(session, `document.body?.innerText.includes(${JSON.stringify(text)}) ?? false`), text);
}

async function screenshot(session, name) {
  const result = await session.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  writeFileSync(join(artifacts, name), Buffer.from(result.data, 'base64'));
}

async function navigate(session, base, token) {
  await session.send('Page.navigate', { url: `${base}/respond#${token}` });
  await visible(session, '예시 행사');
  await visible(session, '응답하기');
}

try {
  const port = await availablePort();
  const base = `http://127.0.0.1:${port}`;
  app = buildApp({ adminToken, encryptionKey, databasePath: ':memory:', publicBaseUrl: base, webDist: resolve('apps/web/dist') });
  const event = {
    id: eventId, clubName: '가상 동아리', title: '예시 행사', startsAt: '2029-10-20T10:00:00Z',
    location: '가상 회의실', feeAmount: 5000, capacity: 20, deadline: null,
    notice: '예시 안내만 포함됩니다.', cancelled: false, version: 0,
    pollOptions: [{ id: 'date-a', label: '후보 A' }, { id: 'date-b', label: '후보 B' }], pollDeadline: null,
  };
  await admin('POST', '/api/admin/events', { operationId: randomUUID(), expectedVersion: 0, event });
  const common = await link('common');
  const personal = await link('personal');
  await app.listen({ host: '127.0.0.1', port });
  const empty = await admin('GET', `/api/admin/events/${eventId}/responses`);
  assert.deepEqual(empty.submissions, []);
  assert.deepEqual(empty.paymentRequests, []);

  browser = spawn(chrome, ['--headless', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--remote-allow-origins=*', `--user-data-dir=${profile}`, '--remote-debugging-port=0', '--window-size=390,844', 'about:blank'], { stdio: 'ignore' });
  const active = join(profile, 'DevToolsActivePort');
  await waitFor(() => existsSync(active) && readFileSync(active, 'utf8').trim(), 'Chrome DevTools port', 15000);
  const debuggerPort = Number(readFileSync(active, 'utf8').split('\n')[0]);
  const target = await bounded(fetch(`http://127.0.0.1:${debuggerPort}/json/new?about:blank`, { method: 'PUT' }).then((r) => r.json()), 'Chrome target');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await bounded(new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); }), 'CDP connect');
  const session = cdp(socket);
  await session.send('Page.enable');
  await session.send('Runtime.enable');
  await session.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });

  await navigate(session, base, common);
  assert.equal(await evaluate(session, 'document.documentElement.scrollWidth <= innerWidth'), true, '390px detail overflow');
  await screenshot(session, 'member-detail.png');
  assert.deepEqual((await admin('GET', `/api/admin/events/${eventId}/responses`)).submissions, []);
  await click(session, '응답하기');
  await fill(session, '이름', '가상 회원 A');
  await choose(session, '참석');
  await choose(session, '안내사항을 확인했어요');
  await choose(session, '후보 A');
  await click(session, '입력 내용 확인');
  await visible(session, '제출할 내용');
  await click(session, '응답 제출');
  await visible(session, '응답을 저장했어요');
  await visible(session, '운영자 확인 대기');
  await screenshot(session, 'member-response.png');
  const editToken = await evaluate(session, 'location.hash.slice(1)');
  assert.notEqual(editToken, common, 'common response must receive a limited edit token');
  const own = await app.inject({ url: '/api/respond', headers: { authorization: `Bearer ${editToken}` } });
  assert.equal(own.json().access.kind, 'submission');
  assert.equal(own.json().access.canPay, false);
  let snapshot = await admin('GET', `/api/admin/events/${eventId}/responses`);
  assert.equal(snapshot.submissions.length, 1);
  assert.equal(snapshot.submissions[0].verification, 'pending');
  assert.equal(snapshot.submissions[0].memberId, null);

  await admin('POST', '/api/admin/events', { operationId: randomUUID(), expectedVersion: 1, event: { ...event, version: 1, notice: '최신 예시 안내를 다시 확인해 주세요.' } });
  await session.send('Page.reload');
  await visible(session, '행사 안내가 변경됐어요');
  await click(session, '응답 수정');
  assert.equal(await evaluate(session, `document.querySelector('input[type=checkbox]')?.checked`), false, 'changed guidance requires a fresh explicit acknowledgement');
  await screenshot(session, 'member-changed-event.png');

  await navigate(session, base, personal);
  await click(session, '입금 확인 요청');
  await fill(session, '금액 (원)', '5000');
  await fill(session, '입금자명', '가상 입금자');
  await fill(session, '입금 시각', '2026-10-01T12:00');
  await click(session, '입력 내용 확인');
  await visible(session, '요청할 내용');
  await click(session, '입금 확인 요청 제출');
  await visible(session, '입금 확인을 요청했어요');
  await screenshot(session, 'member-payment.png');
  snapshot = await admin('GET', `/api/admin/events/${eventId}/responses`);
  assert.equal(snapshot.paymentRequests.length, 1);
  assert.equal(snapshot.paymentRequests[0].status, 'pending');

  const interrupted = await link('common');
  await navigate(session, base, interrupted);
  await click(session, '응답하기');
  await fill(session, '이름', '가상 회원 B');
  await choose(session, '불참');
  await click(session, '입력 내용 확인');
  await visible(session, '제출할 내용');
  let responseDropped;
  const dropped = bounded(new Promise((resolve) => { responseDropped = resolve; }), 'response interception');
  session.on('Fetch.requestPaused', async (paused) => {
    if (paused.request.method === 'POST' && paused.responseStatusCode === 200) {
      try { await session.send('Fetch.failRequest', { requestId: paused.requestId, errorReason: 'ConnectionClosed' }); responseDropped(); }
      catch (error) { responseDropped(error); }
    } else await session.send('Fetch.continueRequest', { requestId: paused.requestId });
  });
  await session.send('Fetch.enable', { patterns: [{ urlPattern: '*api/respond', requestStage: 'Response' }] });
  await click(session, '응답 제출');
  assert.equal(await dropped, undefined);
  await visible(session, '응답 저장 결과 미확인');
  assert.equal(await evaluate(session, `!document.body.innerText.includes('응답을 저장했어요')`), true);
  assert.equal(await evaluate(session, `document.querySelector('input') === null`), true, 'uncertain draft stays locked');
  snapshot = await admin('GET', `/api/admin/events/${eventId}/responses`);
  assert.equal(snapshot.submissions.length, 2, 'server saved despite lost browser response');
  await session.send('Fetch.disable');
  session.off('Fetch.requestPaused');
  await session.send('Page.reload');
  await visible(session, '응답 저장 결과 미확인');
  assert.equal(await evaluate(session, `document.querySelector('input') === null`), true, 'pending request survives a hard reload');
  await click(session, '원래 응답 다시 확인');
  await visible(session, '응답을 저장했어요');
  assert.notEqual(await evaluate(session, 'location.hash.slice(1)'), interrupted, 'retry recovers edit token');
  snapshot = await admin('GET', `/api/admin/events/${eventId}/responses`);
  assert.equal(snapshot.submissions.length, 2, 'retry did not create a duplicate');
  assert.equal(snapshot.submissions.find((item) => item.name === '가상 회원 B')?.version, 1);
  console.log('PASS browser smoke: 390px detail, common response/edit token, pending payment, lost-response retry');
  console.log(`Screenshots: ${artifacts}/member-{detail,response,payment}.png`);
} finally {
  socket?.close();
  if (browser && browser.exitCode === null && browser.signalCode === null) {
    const closed = once(browser, 'close');
    browser.kill();
    await bounded(closed, 'Chrome shutdown');
  }
  await app?.close();
  rmSync(temporary, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
