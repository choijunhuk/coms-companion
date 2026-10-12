import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdtempSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {buildApp, type EventView} from './app.ts';

const adminToken = 'test-admin-credential-'.repeat(3);
const key = 'a1'.repeat(32);
const auth = {authorization: `Bearer ${adminToken}`};
const event = (): EventView => ({id:randomUUID(),clubName:'가상 동아리',title:'예시 행사',startsAt:null,location:'미정',feeAmount:10000,capacity:2,deadline:null,notice:'가상 안내',cancelled:false,version:0,pollOptions:[{id:'date-a',label:'후보 A'},{id:'date-b',label:'후보 B'}],pollDeadline:null});

async function setup(options: {databasePath?:string; now?:()=>Date}={}) {
  const app=buildApp({adminToken,encryptionKey:key,databasePath:options.databasePath??':memory:',publicBaseUrl:'https://example.invalid',now:options.now});
  await app.ready();
  const e=event();
  const published=await app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId:randomUUID(),expectedVersion:0,event:e}});
  assert.equal(published.statusCode,200,published.body);
  return {app,e:published.json().event as EventView};
}
async function invite(app: ReturnType<typeof buildApp>, e:EventView, kind='personal', memberId=randomUUID(), scopes?:string[]) {
  const reply=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/links`,headers:auth,payload:{operationId:randomUUID(),kind,memberId:kind==='personal'?memberId:undefined,expiresAt:'2030-01-01T00:00:00Z',scopes}});
  assert.equal(reply.statusCode,200,reply.body);
  return reply.json() as {token:string;linkId:string;url:string};
}
const answer=(e:EventView, extra:Record<string,unknown>={})=>({operationId:randomUUID(),expectedVersion:0,eventVersion:e.version,name:'예시 회원',rsvp:'attending',noticeAcknowledged:true,selectedOptions:['date-a'],...extra});
const bearer=(token:string)=>({authorization:`Bearer ${token}`});

test('auth required and GET/preview never writes an RSVP or acknowledgement',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());
  const link=await invite(app,e);
  assert.equal((await app.inject('/api/respond')).statusCode,401);
  assert.equal((await app.inject({url:`/api/admin/events/${e.id}/responses`})).statusCode,401);
  for(let n=0;n<3;n++){
    const get=await app.inject({url:'/api/respond',headers:bearer(link.token)});
    assert.equal(get.statusCode,200);assert.equal(get.json().submission,null);
    assert.equal(get.headers['cache-control'],'no-store');
  }
  assert.deepEqual((await app.inject({url:`/api/admin/events/${e.id}/responses`,headers:auth})).json().submissions,[]);
});

test('encoded admin route aliases require route-bound authentication for reads and writes',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());
  const write=await app.inject({method:'POST',url:'/api/%61dmin/events',payload:{operationId:randomUUID(),expectedVersion:0,event:event()}});
  assert.equal(write.statusCode,401);
  const read=await app.inject(`/api/%61dmin/events/${e.id}/responses`);
  assert.equal(read.statusCode,401);
});

test('a successful common submission can recover its edit receipt after event deadline',async t=>{
  let clock=new Date('2026-10-12T11:59:00Z');
  const {app,e}=await setup({now:()=>clock});t.after(()=>app.close());
  const updated=await app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId:randomUUID(),expectedVersion:e.version,event:{...e,deadline:'2026-10-12T12:00:00Z'}}});
  const current=updated.json().event as EventView,link=await invite(app,current,'common'),payload=answer(current);
  const saved=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload});
  assert.equal(saved.statusCode,200);
  clock=new Date('2026-10-12T12:01:00Z');
  const recovered=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload});
  assert.equal(recovered.statusCode,200);assert.deepEqual(recovered.json(),saved.json());
  const fresh=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(current)});
  assert.equal(fresh.json().error.code,'CLOSED');
});

test('personal save/retry/edit has one response and stale versions cannot overwrite it',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());const link=await invite(app,e);
  const payload=answer(e);
  const first=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload});
  assert.equal(first.statusCode,200,first.body);
  const replay=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload});
  assert.deepEqual(replay.json(),first.json());
  const mismatch=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:{...payload,rsvp:'declined'}});
  assert.equal(mismatch.json().error.code,'OPERATION_CONFLICT');
  const stale=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e)});
  assert.equal(stale.statusCode,409);assert.equal(stale.json().current.version,1);
  const edit=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e,{expectedVersion:1,rsvp:'declined'})});
  assert.equal(edit.json().submission.version,2);
  assert.equal((await app.inject({url:`/api/admin/events/${e.id}/responses`,headers:auth})).json().submissions.length,1);
});

test('common names never link members; edit token sees only its own submission and no payments',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());const link=await invite(app,e,'common');
  const first=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e)});
  assert.equal(first.statusCode,200,first.body);const token=first.json().editToken;
  assert.equal(first.json().submission.verification,'pending');
  const get=await app.inject({url:'/api/respond',headers:bearer(token)});
  assert.equal(get.json().access.kind,'submission');assert.equal(get.json().access.canPay,false);
  const payment=await app.inject({method:'POST',url:'/api/respond/payments',headers:bearer(token),payload:{operationId:randomUUID(),eventVersion:e.version,amount:10000,depositorName:'예시',depositedAt:'2026-10-12T00:00:00Z'}});
  assert.equal(payment.statusCode,403);
  const all=(await app.inject({url:`/api/admin/events/${e.id}/responses`,headers:auth})).json();
  assert.equal(all.submissions[0].memberId,null);
});

test('capacity, expiry, revocation, event changes and cancellation block explicit saves',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());
  for(let n=0;n<2;n++){
    const link=await invite(app,e);assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e)})).statusCode,200);
  }
  const extra=await invite(app,e);const full=await app.inject({method:'POST',url:'/api/respond',headers:bearer(extra.token),payload:answer(e)});
  assert.equal(full.json().error.code,'CAPACITY');
  const changed=await app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId:randomUUID(),expectedVersion:e.version,event:{...e,title:'변경 안내'}}});
  assert.equal(changed.json().event.version,2);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(extra.token),payload:answer(e,{rsvp:'declined'})})).json().error.code,'EVENT_CONFLICT');
  await app.inject({method:'POST',url:`/api/admin/links/${extra.linkId}/revoke`,headers:auth,payload:{operationId:randomUUID()}});
  assert.equal((await app.inject({url:'/api/respond',headers:bearer(extra.token)})).json().access.state,'revoked');
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(extra.token),payload:answer(e)})).statusCode,403);
  const current=changed.json().event;
  const cancelled=await app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId:randomUUID(),expectedVersion:2,event:{...current,cancelled:true}}});
  const newLink=await invite(app,current);
  assert.equal((await app.inject({url:'/api/respond',headers:bearer(newLink.token)})).json().access.state,'cancelled');
  assert.equal(cancelled.statusCode,200);
});

test('payment claim is pending, idempotent and never confirms a ledger; review requires ledger id',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());const link=await invite(app,e);
  const payload={operationId:randomUUID(),eventVersion:e.version,amount:5000,depositorName:'가상 입금자',depositedAt:'2026-10-12T00:00:00Z'};
  const req=await app.inject({method:'POST',url:'/api/respond/payments',headers:bearer(link.token),payload});
  assert.equal(req.statusCode,200,req.body);assert.equal(req.json().request.status,'pending');
  assert.deepEqual((await app.inject({method:'POST',url:'/api/respond/payments',headers:bearer(link.token),payload})).json(),req.json());
  const noLedger=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/payments/${req.json().request.id}/review`,headers:auth,payload:{operationId:randomUUID(),status:'confirmed'}});
  assert.equal(noLedger.statusCode,400);
  const snapshot=(await app.inject({url:`/api/admin/events/${e.id}/responses`,headers:auth})).json();
  assert.equal(snapshot.paymentRequests.length,1);assert.equal(snapshot.paymentRequests[0].status,'pending');
});

test('deadline and poll deadline use server time, unknown options and invalid amounts are rejected',async t=>{
  const {app,e}=await setup({now:()=>new Date('2026-10-12T12:00:00Z')});t.after(()=>app.close());const link=await invite(app,e);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e,{selectedOptions:['unknown']})})).statusCode,400);
  assert.equal((await app.inject({method:'POST',url:'/api/respond/payments',headers:bearer(link.token),payload:{operationId:randomUUID(),eventVersion:e.version,amount:-1,depositorName:'예시',depositedAt:'2026-10-12T00:00:00Z'}})).statusCode,400);
  const update=await app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId:randomUUID(),expectedVersion:1,event:{...e,deadline:'2026-10-12T11:00:00Z'}}});
  const closed=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(update.json().event)});
  assert.equal(closed.json().error.code,'CLOSED');
});

test('SQLite restart preserves saved responses and encrypted operation receipts',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'companion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const databasePath=join(dir,'test.sqlite');
  const {app,e}=await setup({databasePath});const link=await invite(app,e,'common');const payload=answer(e);
  const first=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload});assert.equal(first.statusCode,200,first.body);await app.close();
  const restarted=buildApp({adminToken,encryptionKey:key,databasePath,publicBaseUrl:'https://example.invalid'});t.after(()=>restarted.close());
  assert.deepEqual((await restarted.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload})).json(),first.json());
  assert.equal((await restarted.inject({url:'/api/respond',headers:bearer(first.json().editToken)})).json().submission.version,1);
});

test('expired or revoked tokens cannot recover private receipts or payment data',async t=>{
  let clock=new Date('2026-10-12T12:00:00Z');
  const {app,e}=await setup({now:()=>clock});t.after(()=>app.close());const link=await invite(app,e);const payload=answer(e);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload})).statusCode,200);
  clock=new Date('2031-01-01T00:00:00Z');
  const expired=await app.inject({url:'/api/respond',headers:bearer(link.token)});
  assert.equal(expired.json().access.state,'expired');assert.equal(expired.json().submission,null);assert.deepEqual(expired.json().paymentRequests,[]);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload})).statusCode,410);
  clock=new Date('2026-10-12T12:00:00Z');
  await app.inject({method:'POST',url:`/api/admin/links/${link.linkId}/revoke`,headers:auth,payload:{operationId:randomUUID()}});
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload})).statusCode,403);
});

test('payment-only links cannot change responses and operation receipts are bound to identity',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());
  const limited=await invite(app,e,'personal',randomUUID(),['payment']);
  const context=(await app.inject({url:'/api/respond',headers:bearer(limited.token)})).json();
  assert.deepEqual(context.access.scopes,['payment']);assert.equal(context.access.canPay,true);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(limited.token),payload:answer(e)})).statusCode,403);
  const first=await invite(app,e),second=await invite(app,e),payload=answer(e);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(first.token),payload})).statusCode,200);
  const stolen=await app.inject({method:'POST',url:'/api/respond',headers:bearer(second.token),payload});
  assert.equal(stolen.json().error.code,'OPERATION_CONFLICT');
});

test('explicitly linked common responses stay linked when their edit token changes RSVP',async t=>{
  const {app,e}=await setup();t.after(()=>app.close());const link=await invite(app,e,'common');
  const submitted=(await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e)})).json();
  const memberId=randomUUID();
  const linked=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/submissions/${submitted.submission.id}/link`,headers:auth,payload:{operationId:randomUUID(),expectedVersion:1,memberId}});
  assert.equal(linked.statusCode,200,linked.body);
  const edited=await app.inject({method:'POST',url:'/api/respond',headers:bearer(submitted.editToken),payload:answer(e,{expectedVersion:2,rsvp:'declined'})});
  assert.equal(edited.statusCode,200,edited.body);assert.equal(edited.json().submission.verification,'linked');
  const snapshot=(await app.inject({url:`/api/admin/events/${e.id}/responses`,headers:auth})).json();
  assert.equal(snapshot.submissions[0].memberId,memberId);
});

test('removed poll candidates retain historical selection after voting closes without blocking RSVP edits',async t=>{
  let clock=new Date('2026-10-12T11:00:00Z');
  const {app,e}=await setup({now:()=>clock});t.after(()=>app.close());const link=await invite(app,e);
  assert.equal((await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(e)})).statusCode,200);
  const changed=await app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId:randomUUID(),expectedVersion:1,event:{...e,pollOptions:[{id:'date-b',label:'남은 후보'}],pollDeadline:'2026-10-12T12:00:00Z'}}});
  clock=new Date('2026-10-12T13:00:00Z');
  const current=changed.json().event;
  const retained=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(current,{expectedVersion:1,rsvp:'declined'})});
  assert.equal(retained.statusCode,200,retained.body);assert.deepEqual(retained.json().submission.selectedOptions,['date-a']);
  const newVote=await app.inject({method:'POST',url:'/api/respond',headers:bearer(link.token),payload:answer(current,{expectedVersion:2,selectedOptions:['date-b']})});
  assert.equal(newVote.json().error.code,'POLL_CLOSED');
});
