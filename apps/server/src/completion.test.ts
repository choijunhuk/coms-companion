import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdtempSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {DatabaseSync} from 'node:sqlite';
import {buildApp, type EventView} from './app.ts';

const adminToken='test-admin-credential-'.repeat(3), encryptionKey='a1'.repeat(32);
const auth={authorization:`Bearer ${adminToken}`};
const memberAuth=(token:string)=>({authorization:`Bearer ${token}`});
const createApp=(databasePath=':memory:')=>buildApp({adminToken,encryptionKey,databasePath,publicBaseUrl:'https://example.invalid',now:()=>new Date('2026-10-12T12:00:00Z')});
const event=(capacity:number|null=2):EventView=>({id:randomUUID(),clubName:'가상 동아리',title:'가상 행사',startsAt:null,location:'미정',feeAmount:10000,capacity,deadline:null,notice:'가상 안내',cancelled:false,version:0,pollOptions:[{id:'date-a',label:'후보 A'}],pollDeadline:null});
const manual=(memberId=randomUUID())=>({id:randomUUID(),operationId:randomUUID(),memberId,name:'가상 회원',rsvp:'attending',noticeAcknowledged:true,selectedOptions:['date-a']});
const create=(app:ReturnType<typeof buildApp>, e=event(), initialResponses:unknown[]=[], operationId=randomUUID())=>app.inject({method:'POST',url:'/api/admin/events',headers:auth,payload:{operationId,expectedVersion:0,event:e,initialResponses}});
const snapshot=(app:ReturnType<typeof buildApp>,id:string)=>app.inject({url:`/api/admin/events/${id}/responses`,headers:auth});
const changes=(app:ReturnType<typeof buildApp>,cursor=0)=>app.inject({url:`/api/admin/changes?cursor=${cursor}`,headers:auth});
async function link(app:ReturnType<typeof buildApp>,eventId:string,memberId=randomUUID()) {
  const reply=await app.inject({method:'POST',url:`/api/admin/events/${eventId}/links`,headers:auth,payload:{operationId:randomUUID(),kind:'personal',memberId,expiresAt:'2030-01-01T00:00:00Z'}});
  assert.equal(reply.statusCode,200,reply.body);
  return reply.json() as {token:string};
}
async function issuedLink(app:ReturnType<typeof buildApp>,eventId:string,kind:'common'|'personal',memberId?:string) {
  const operationId=randomUUID();
  const reply=await app.inject({method:'POST',url:`/api/admin/events/${eventId}/links`,headers:auth,payload:{operationId,kind,memberId,expiresAt:'2030-01-01T00:00:00Z'}});
  assert.equal(reply.statusCode,200,reply.body);
  return {...reply.json() as {token:string;linkId:string},operationId};
}
const revokeIssued=(app:ReturnType<typeof buildApp>,eventId:string,issuedOperationId:string,operationId=randomUUID())=>app.inject({method:'POST',url:`/api/admin/events/${eventId}/links/revoke`,headers:auth,payload:{operationId,issuedOperationId}});
const submit=(app:ReturnType<typeof buildApp>,token:string)=>app.inject({method:'POST',url:'/api/respond',headers:memberAuth(token),payload:{operationId:randomUUID(),expectedVersion:0,eventVersion:1,name:'가상 회원',rsvp:'attending',noticeAcknowledged:true,selectedOptions:['date-a']}});
async function claim(app:ReturnType<typeof buildApp>,eventId:string,amount=5000) {
  const invite=await link(app,eventId);
  const reply=await app.inject({method:'POST',url:'/api/respond/payments',headers:memberAuth(invite.token),payload:{operationId:randomUUID(),eventVersion:1,amount,depositorName:'가상 입금자',depositedAt:'2026-10-12T11:00:00Z'}});
  assert.equal(reply.statusCode,200,reply.body);
  return {token:invite.token,id:reply.json().request.id as string};
}
const review=(app:ReturnType<typeof buildApp>,eventId:string,requestId:string,payload:Record<string,unknown>,phase:'prepare'|'commit')=>app.inject({method:'POST',url:`/api/admin/events/${eventId}/payments/${requestId}/review/${phase}`,headers:auth,payload});
const intent=()=>({operationId:randomUUID(),reviewId:randomUUID(),ledgerEntryId:randomUUID(),chargeId:randomUUID(),confirmedAmount:7000,ownerId:randomUUID(),reason:'가상 입금 확인'});

test('link creation accepts Kotlin Instant nanoseconds and exact request replay returns the same token',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);
  const payload={operationId:randomUUID(),kind:'personal',memberId:randomUUID(),expiresAt:'2030-01-01T00:00:00.123456789Z'};
  const request=()=>app.inject({method:'POST',url:`/api/admin/events/${e.id}/links`,headers:auth,payload});
  const first=await request();assert.equal(first.statusCode,200,first.body);
  assert.deepEqual((await request()).json(),first.json());
  assert.equal((await appSafeContext(app,first.json().token)).access.state,'open');
});

test('initial manual responses retain operator source, member IDs and accepted versions',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event(),rows=[manual(),manual()];
  const reply=await create(app,e,rows);assert.equal(reply.statusCode,200,reply.body);
  assert.deepEqual(reply.json().initialResponses.map((r:{memberId:string})=>r.memberId),rows.map(r=>r.memberId));
  const saved=(await snapshot(app,e.id)).json().submissions;
  assert.equal(saved.length,2);assert.ok(saved.every((r:{source:string;version:number;verification:string})=>r.source==='operator'&&r.version===1&&r.verification==='linked'));
});

test('invalid initial manual response rolls back event and earlier responses as one transaction',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event(),first=manual();
  const failed=await create(app,e,[first,{...manual(),memberId:first.memberId}]);
  assert.equal(failed.statusCode,400,failed.body);
  assert.equal((await snapshot(app,e.id)).statusCode,404);
  assert.deepEqual((await changes(app)).json().changes,[]);
});

test('initial manual responses obey event capacity without persisting a partial batch',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event(1);
  const failed=await create(app,e,[manual(),manual()]);
  assert.equal(failed.json().error.code,'CAPACITY');
  assert.equal((await snapshot(app,e.id)).statusCode,404);
});

test('duplicate initial response IDs roll back the whole event creation',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event(),first=manual();
  const failed=await create(app,e,[first,{...manual(),id:first.id}]);
  assert.equal(failed.statusCode,400,failed.body);
  assert.equal((await snapshot(app,e.id)).statusCode,404);
});

test('outer event operation replay returns the same imported response IDs after restart',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const path=join(dir,'db.sqlite'),e=event(),rows=[manual()],operationId=randomUUID(),app=createApp(path);
  const first=await create(app,e,rows,operationId);assert.equal(first.statusCode,200,first.body);await app.close();
  const restarted=createApp(path);t.after(()=>restarted.close());
  const replay=await create(restarted,e,rows,operationId);
  assert.deepEqual(replay.json(),first.json());
  assert.equal((await snapshot(restarted,e.id)).json().submissions.length,1);
});

test('operator proposal conflicts with a member response and preserves the member answer',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();assert.equal((await create(app,e)).statusCode,200);
  const memberId=randomUUID(),invite=await link(app,e.id,memberId);
  const response=await app.inject({method:'POST',url:'/api/respond',headers:memberAuth(invite.token),payload:{operationId:randomUUID(),expectedVersion:0,eventVersion:1,name:'가상 회원',rsvp:'declined',noticeAcknowledged:false,selectedOptions:[]}});
  assert.equal(response.statusCode,200,response.body);
  const proposed=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/submissions`,headers:auth,payload:{...manual(memberId),expectedVersion:0,eventVersion:1}});
  assert.equal(proposed.statusCode,409);assert.equal(proposed.json().current.id,response.json().submission.id);
  assert.equal((await snapshot(app,e.id)).json().submissions[0].rsvp,'declined');
});

test('operator proposal with a stale event version leaves no response behind',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);
  const proposed=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/submissions`,headers:auth,payload:{...manual(),expectedVersion:0,eventVersion:0}});
  assert.equal(proposed.statusCode,409);assert.equal(proposed.json().error.code,'EVENT_CONFLICT');
  assert.deepEqual((await snapshot(app,e.id)).json().submissions,[]);
});

test('change cursor pages at 100 with stable sequence and can replay after restart',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const path=join(dir,'db.sqlite'),app=createApp(path);
  for(let n=0;n<101;n++)assert.equal((await create(app,event(null))).statusCode,200);
  const first=(await changes(app)).json();assert.equal(first.changes.length,100);assert.equal(first.hasMore,true);
  assert.ok(first.changes.every((r:{kind:string})=>r.kind==='event'));
  assert.deepEqual(first.changes.map((r:{sequence:number})=>r.sequence),Array.from({length:100},(_,n)=>n+1));
  await app.close();const restarted=createApp(path);t.after(()=>restarted.close());
  const second=(await changes(restarted,first.nextCursor)).json();
  assert.equal(second.changes.length,1);assert.equal(second.changes[0].sequence,101);assert.equal(second.hasMore,false);
});

test('changes require admin auth even through an encoded route alias and reject a cursor ahead of the log',async t=>{
  const app=createApp();t.after(()=>app.close());assert.equal((await create(app)).statusCode,200);
  assert.equal((await app.inject('/api/admin/changes')).statusCode,401);
  assert.equal((await app.inject('/api/%61dmin/changes')).statusCode,401);
  const ahead=await changes(app,999);assert.equal(ahead.statusCode,409);assert.equal(ahead.json().error.code,'CURSOR_RESET');
});

test('startup seeds old event and response records into the change cursor once',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const path=join(dir,'old.sqlite'),e={...event(),version:1},r=manual();
  const db=new DatabaseSync(path);
  db.exec('CREATE TABLE events(id TEXT PRIMARY KEY,payload TEXT NOT NULL); CREATE TABLE submissions(id TEXT PRIMARY KEY,event_id TEXT NOT NULL,member_id TEXT,payload TEXT NOT NULL)');
  db.prepare('INSERT INTO events VALUES(?,?)').run(e.id,JSON.stringify(e));
  db.prepare('INSERT INTO submissions VALUES(?,?,?,?)').run(r.id,e.id,r.memberId,JSON.stringify({...r,eventId:e.id,version:1,eventVersion:1,updatedAt:'2026-10-12T11:00:00Z',verification:'linked',source:'operator'}));db.close();
  const app=createApp(path);assert.equal((await changes(app)).json().changes.length,2);await app.close();
  const restarted=createApp(path);t.after(()=>restarted.close());assert.equal((await changes(restarted)).json().changes.length,2);
});

test('prepare reserves review metadata while the public payment claim remains pending',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const c=await claim(app,e.id),body=intent();
  const prepared=await review(app,e.id,c.id,body,'prepare');assert.equal(prepared.statusCode,200,prepared.body);
  assert.equal(prepared.json().review.state,'reserved');assert.equal(prepared.json().request.status,'pending');
  const publicClaim=(await app.inject({url:'/api/respond',headers:memberAuth(c.token)})).json().paymentRequests[0];
  assert.equal(publicClaim.status,'pending');
  for(const key of ['review','reviewId','ownerId','chargeId','ledgerEntryId','confirmedAmount'])assert.equal(publicClaim[key],undefined,key);
});

test('prepare replay after restart keeps one reservation and one payment change',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const path=join(dir,'db.sqlite'),app=createApp(path),e=event();
  await create(app,e);const c=await claim(app,e.id),body=intent(),first=await review(app,e.id,c.id,body,'prepare');assert.equal(first.statusCode,200,first.body);
  const cursor=(await changes(app)).json().nextCursor;await app.close();
  const restarted=createApp(path);t.after(()=>restarted.close());
  assert.deepEqual((await review(restarted,e.id,c.id,body,'prepare')).json(),first.json());
  assert.deepEqual((await changes(restarted,cursor)).json().changes,[]);
});

test('a competing owner cannot reserve an already reviewed claim',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const c=await claim(app,e.id),first=intent();
  assert.equal((await review(app,e.id,c.id,first,'prepare')).statusCode,200);
  const competing=await review(app,e.id,c.id,{...first,operationId:randomUUID(),ownerId:randomUUID()},'prepare');
  assert.equal(competing.statusCode,409);assert.equal(competing.json().error.code,'REVIEW_CONFLICT');
  assert.equal((await snapshot(app,e.id)).json().paymentRequests[0].review.ownerId,first.ownerId);
});

test('the same review ID with changed confirmed amount cannot replace its reservation',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const c=await claim(app,e.id),first=intent();
  assert.equal((await review(app,e.id,c.id,first,'prepare')).statusCode,200);
  const conflict=await review(app,e.id,c.id,{...first,operationId:randomUUID(),confirmedAmount:9000},'prepare');
  assert.equal(conflict.statusCode,409);assert.equal(conflict.json().error.code,'REVIEW_CONFLICT');
  assert.equal((await snapshot(app,e.id)).json().paymentRequests[0].review.confirmedAmount,7000);
});

test('ledger entry ID cannot be reserved for two distinct claims',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const a=await claim(app,e.id),b=await claim(app,e.id),body=intent();
  assert.equal((await review(app,e.id,a.id,body,'prepare')).statusCode,200);
  const conflict=await review(app,e.id,b.id,{...intent(),ledgerEntryId:body.ledgerEntryId},'prepare');
  assert.equal(conflict.statusCode,409);assert.equal(conflict.json().error.code,'REVIEW_CONFLICT');
  assert.equal((await snapshot(app,e.id)).json().paymentRequests.find((r:{id:string})=>r.id===b.id).status,'pending');
});

test('commit requires the exact reservation and retry creates no extra change',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const c=await claim(app,e.id),body=intent();
  await review(app,e.id,c.id,body,'prepare');
  const mismatch=await review(app,e.id,c.id,{operationId:randomUUID(),reviewId:body.reviewId,ledgerEntryId:randomUUID()},'commit');
  assert.equal(mismatch.statusCode,409);assert.equal((await snapshot(app,e.id)).json().paymentRequests[0].status,'pending');
  const commit={operationId:randomUUID(),reviewId:body.reviewId,ledgerEntryId:body.ledgerEntryId};
  const first=await review(app,e.id,c.id,commit,'commit');assert.equal(first.statusCode,200,first.body);assert.equal(first.json().review.state,'confirmed');
  const cursor=(await changes(app)).json().nextCursor;
  assert.deepEqual((await review(app,e.id,c.id,commit,'commit')).json(),first.json());
  assert.deepEqual((await changes(app,cursor)).json().changes,[]);
});

test('commit replay after restart keeps one confirmed review and no extra change',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const path=join(dir,'db.sqlite'),app=createApp(path),e=event();
  await create(app,e);const c=await claim(app,e.id),body=intent();await review(app,e.id,c.id,body,'prepare');
  const commit={operationId:randomUUID(),reviewId:body.reviewId,ledgerEntryId:body.ledgerEntryId};
  const first=await review(app,e.id,c.id,commit,'commit');assert.equal(first.statusCode,200,first.body);
  const cursor=(await changes(app)).json().nextCursor;await app.close();
  const restarted=createApp(path);t.after(()=>restarted.close());
  assert.deepEqual((await review(restarted,e.id,c.id,commit,'commit')).json(),first.json());
  assert.deepEqual((await changes(restarted,cursor)).json().changes,[]);
  assert.equal((await snapshot(restarted,e.id)).json().paymentRequests[0].review.state,'confirmed');
});

test('member sees the confirmed amount when it differs from the submitted claim',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const c=await claim(app,e.id,5000),body=intent();
  await review(app,e.id,c.id,body,'prepare');
  const committed=await review(app,e.id,c.id,{operationId:randomUUID(),reviewId:body.reviewId,ledgerEntryId:body.ledgerEntryId},'commit');
  assert.equal(committed.statusCode,200,committed.body);
  const publicClaim=(await app.inject({url:'/api/respond',headers:memberAuth(c.token)})).json().paymentRequests[0];
  assert.equal(publicClaim.amount,5000);
  assert.equal(publicClaim.confirmedAmount,7000);
  for(const key of ['review','reviewId','ownerId','chargeId','ledgerEntryId'])assert.equal(publicClaim[key],undefined,key);
});

test('reserved claim cannot be rejected through the legacy review endpoint',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const c=await claim(app,e.id),body=intent();await review(app,e.id,c.id,body,'prepare');
  const reject=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/payments/${c.id}/review`,headers:auth,payload:{operationId:randomUUID(),status:'rejected',reason:'가상 반려'}});
  assert.equal(reject.statusCode,409);assert.equal((await snapshot(app,e.id)).json().paymentRequests[0].status,'pending');
});

test('cancelling a personal issued link blocks new saves but preserves its accepted response',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const issued=await issuedLink(app,e.id,'personal',randomUUID());
  const saved=await submit(app,issued.token);assert.equal(saved.statusCode,200,saved.body);
  assert.deepEqual((await revokeIssued(app,e.id,issued.operationId)).json(),{revoked:true});
  const context=(await app.inject({url:'/api/respond',headers:memberAuth(issued.token)})).json();
  assert.equal(context.access.state,'revoked');assert.equal(context.submission,null);assert.deepEqual(context.paymentRequests,[]);
  assert.equal((await submit(app,issued.token)).statusCode,403);
  assert.equal((await snapshot(app,e.id)).json().submissions[0].id,saved.json().submission.id);
});

test('cancelling a common issued link blocks its child edit token',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const issued=await issuedLink(app,e.id,'common');
  const saved=await submit(app,issued.token);assert.equal(saved.statusCode,200,saved.body);const child=saved.json().editToken as string;
  assert.equal((await revokeIssued(app,e.id,issued.operationId)).statusCode,200);
  const context=(await app.inject({url:'/api/respond',headers:memberAuth(child)})).json();
  assert.equal(context.access.state,'revoked');assert.equal(context.submission,null);
  assert.equal((await submit(app,child)).statusCode,403);
  assert.equal((await snapshot(app,e.id)).json().submissions.length,1);
});

test('legacy link-id cancellation also blocks common child edit tokens',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);const issued=await issuedLink(app,e.id,'common');
  const child=(await submit(app,issued.token)).json().editToken as string;
  const cancelled=await app.inject({method:'POST',url:`/api/admin/links/${issued.linkId}/revoke`,headers:auth,payload:{operationId:randomUUID()}});
  assert.equal(cancelled.statusCode,200,cancelled.body);
  assert.equal((await app.inject({url:'/api/respond',headers:memberAuth(child)})).json().access.state,'revoked');
});

test('issued-link cancellation receipt replays after restart without changing saved records',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const path=join(dir,'db.sqlite'),app=createApp(path),e=event();
  await create(app,e);const issued=await issuedLink(app,e.id,'personal',randomUUID());const saved=await submit(app,issued.token);
  const operationId=randomUUID(),first=await revokeIssued(app,e.id,issued.operationId,operationId);assert.equal(first.statusCode,200,first.body);await app.close();
  const restarted=createApp(path);t.after(()=>restarted.close());
  assert.deepEqual((await revokeIssued(restarted,e.id,issued.operationId,operationId)).json(),first.json());
  assert.equal((await appSafeContext(restarted,issued.token)).access.state,'revoked');
  assert.equal((await snapshot(restarted,e.id)).json().submissions[0].id,saved.json().submission.id);
});

test('issued-link cancellation needs admin auth and the exact issuing event',async t=>{
  const app=createApp();t.after(()=>app.close());const a=event(),b=event();await create(app,a);await create(app,b);const issued=await issuedLink(app,a.id,'personal',randomUUID());
  const url=`/api/admin/events/${a.id}/links/revoke`,payload={operationId:randomUUID(),issuedOperationId:issued.operationId};
  assert.equal((await app.inject({method:'POST',url,payload})).statusCode,401);
  assert.equal((await app.inject({method:'POST',url:url.replace('/admin/','/%61dmin/'),payload})).statusCode,401);
  assert.equal((await revokeIssued(app,b.id,issued.operationId)).statusCode,404);
  assert.equal((await appSafeContext(app,issued.token)).access.state,'open');
});

test('member cancellation revokes only personal links for that member and event',async t=>{
  const app=createApp();t.after(()=>app.close());const a=event(),b=event();await create(app,a);await create(app,b);
  const selected=randomUUID(),other=randomUUID(),a1=await issuedLink(app,a.id,'personal',selected),a2=await issuedLink(app,a.id,'personal',selected),unrelated=await issuedLink(app,a.id,'personal',other),otherEvent=await issuedLink(app,b.id,'personal',selected);
  const reply=await app.inject({method:'POST',url:`/api/admin/events/${a.id}/links/revoke-member`,headers:auth,payload:{operationId:randomUUID(),memberId:selected}});
  assert.equal(reply.statusCode,200,reply.body);assert.deepEqual(reply.json(),{revokedCount:2});
  for(const issued of [a1,a2])assert.equal((await appSafeContext(app,issued.token)).access.state,'revoked');
  for(const issued of [unrelated,otherEvent])assert.equal((await appSafeContext(app,issued.token)).access.state,'open');
});

test('member cancellation revokes linked common edit token while other edits and the common link stay live',async t=>{
  const app=createApp();t.after(()=>app.close());const e=event();await create(app,e);
  const common=await issuedLink(app,e.id,'common'),selected=await submit(app,common.token),other=await submit(app,common.token);
  assert.equal(selected.statusCode,200,selected.body);assert.equal(other.statusCode,200,other.body);
  const selectedMember=randomUUID(),linked=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/submissions/${selected.json().submission.id}/link`,headers:auth,payload:{operationId:randomUUID(),expectedVersion:1,memberId:selectedMember}});
  assert.equal(linked.statusCode,200,linked.body);
  const cancelled=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/links/revoke-member`,headers:auth,payload:{operationId:randomUUID(),memberId:selectedMember}});
  assert.equal(cancelled.statusCode,200,cancelled.body);assert.deepEqual(cancelled.json(),{revokedCount:1});
  const selectedEdit=selected.json().editToken as string,otherEdit=other.json().editToken as string;
  const context=await appSafeContext(app,selectedEdit);
  assert.equal(context.access.state,'revoked');assert.equal(context.submission,null);assert.deepEqual(context.paymentRequests,[]);
  assert.equal((await submit(app,selectedEdit)).statusCode,403);
  assert.equal((await appSafeContext(app,otherEdit)).access.state,'open');
  assert.equal((await appSafeContext(app,common.token)).access.state,'open');
  assert.equal((await snapshot(app,e.id)).json().submissions.length,2);
});

test('legacy SQLite migration reconnects common edit token to its parent for cancellation',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'completion-test-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));const path=join(dir,'legacy.sqlite'),app=createApp(path),e=event();
  await create(app,e);const issued=await issuedLink(app,e.id,'common');const saved=await submit(app,issued.token);
  assert.equal(saved.statusCode,200,saved.body);const child=saved.json().editToken as string;await app.close();
  const db=new DatabaseSync(path);
  db.exec('PRAGMA foreign_keys=OFF; BEGIN; CREATE TABLE links_legacy(id TEXT PRIMARY KEY,token_hash TEXT UNIQUE NOT NULL,event_id TEXT NOT NULL REFERENCES events(id),kind TEXT NOT NULL,member_id TEXT,submission_id TEXT,scopes TEXT NOT NULL,expires_at TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0); INSERT INTO links_legacy SELECT id,token_hash,event_id,kind,member_id,submission_id,scopes,expires_at,revoked FROM links; DROP TABLE links; ALTER TABLE links_legacy RENAME TO links; COMMIT; PRAGMA user_version=2;');db.close();
  const restarted=createApp(path);t.after(()=>restarted.close());
  assert.equal((await revokeIssued(restarted,e.id,issued.operationId)).statusCode,200);
  assert.equal((await appSafeContext(restarted,child)).access.state,'revoked');
  assert.equal((await snapshot(restarted,e.id)).json().submissions[0].id,saved.json().submission.id);
});

async function appSafeContext(app:ReturnType<typeof buildApp>,token:string) {
  const reply=await app.inject({url:'/api/respond',headers:memberAuth(token)});
  assert.equal(reply.statusCode,200,reply.body);
  return reply.json() as {access:{state:string};submission:unknown;paymentRequests:unknown[]};
}
