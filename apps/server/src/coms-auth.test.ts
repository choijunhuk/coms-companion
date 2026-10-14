import {test, type TestContext} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createServer, type ServerResponse} from 'node:http';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {buildApp, type EventView} from './app.ts';

const adminToken='test-admin-credential-'.repeat(3),encryptionKey='a1'.repeat(32);
const admin={authorization:`Bearer ${adminToken}`};
const memberIdA='616067c5-380a-316d-94f7-6fbe64a9be54'; // Java UUID.nameUUIDFromBytes(namespace + "101")
const memberIdB='5e3fca31-28d0-3829-b5ba-f16babab1aa2'; // Java UUID.nameUUIDFromBytes(namespace + "102")
const roster={source:'coms-eligible-members',updatedAt:'2026-10-14T00:00:00Z',members:[
  {sourceId:'101',studentId:'',name:'등록 회원 A',phone:'010-0000-0001',generation:'27기'},
  {sourceId:'102',studentId:'',name:'등록 회원 B',phone:'010-0000-0002',generation:'27기'}
]};
const event=():EventView=>({id:randomUUID(),clubName:'가상 동아리',title:'가상 행사',startsAt:null,location:'미정',feeAmount:0,capacity:null,deadline:null,notice:'가상 안내',cancelled:false,version:0,pollOptions:[],pollDeadline:null});
const reply=(res:ServerResponse,status:number,body:unknown)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(body));};

async function fixture(t:TestContext,databasePath=':memory:') {
  const seen:{cookie?:string;authorization?:string}[]=[];
  let releaseSlow:undefined|(()=>void);
  const server=createServer(async(req,res)=>{
    seen.push({cookie:req.headers.cookie,authorization:req.headers.authorization});
    const cookie=req.headers.cookie;
    if(cookie==='token=slow')await new Promise<void>(resolve=>{releaseSlow=resolve;});
    if(cookie==='token=redirect'){res.writeHead(302,{Location:'/login'});res.end();return;}
    if(cookie==='token=expired'){reply(res,401,{error:'expired'});return;}
    if(cookie==='token=blocked'){reply(res,403,{error:'blocked'});return;}
    if(cookie==='token=unavailable'){reply(res,500,{error:'upstream'});return;}
    if(cookie==='token=malformed'){reply(res,200,{source:'other',sourceId:'101',name:'가짜'});return;}
    const sourceId=cookie==='token=b'?'102':cookie==='token=outsider'?'103':'101';
    reply(res,200,{source:'coms-eligible-members',sourceId,name:sourceId==='102'?'로그인 회원 B':'로그인 회원 A'});
  });
  await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>{releaseSlow?.();server.closeAllConnections();server.close();});
  const address=server.address();assert.ok(address&&typeof address!=='string');
  const dir=mkdtempSync(join(tmpdir(),'companion-coms-auth-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const rosterPath=join(dir,'roster.json');writeFileSync(rosterPath,JSON.stringify(roster));
  const comsAuthUrl=`http://127.0.0.1:${address.port}/api/companion/me`;
  const app=buildApp({adminToken,encryptionKey,databasePath,publicBaseUrl:'https://coms.kw.ac.kr/companion',rosterPath,comsAuthUrl});
  let closed=false;
  const close=async()=>{if(!closed){closed=true;await app.close();}};
  t.after(close);
  const initial=event();
  const published=await app.inject({method:'POST',url:'/api/admin/events',headers:admin,payload:{operationId:randomUUID(),expectedVersion:0,event:initial}});
  assert.equal(published.statusCode,200,published.body);
  const e=published.json().event as EventView;
  const issued=await app.inject({method:'POST',url:`/api/admin/events/${e.id}/links`,headers:admin,payload:{operationId:randomUUID(),kind:'common',expiresAt:'2030-01-01T00:00:00Z'}});
  assert.equal(issued.statusCode,200,issued.body);
  const link=issued.json() as {token:string;linkId:string};
  const headers=(cookie?:string)=>({authorization:`Bearer ${link.token}`,...(cookie?{cookie}:{})});
  const get=(cookie?:string)=>app.inject({url:'/api/respond',headers:headers(cookie)});
  const post=(cookie:string,payload:Record<string,unknown>)=>app.inject({method:'POST',url:'/api/respond',headers:headers(cookie),payload});
  const answer=(identityMemberId:string,extra:Record<string,unknown>={})=>({operationId:randomUUID(),identityMemberId,expectedVersion:0,eventVersion:e.version,name:'입력한 가짜 이름',rsvp:'attending',noticeAcknowledged:true,selectedOptions:[],...extra});
  return {app,e,link,seen,rosterPath,comsAuthUrl,close,headers,get,post,answer,releaseSlow:()=>releaseSlow?.()};
}

test('COMS common links bind private reads, writes and retries to roster-backed identity',async t=>{
  const f=await fixture(t);
  assert.equal((await f.get()).json().error.code,'COMS_LOGIN_REQUIRED');
  assert.equal((await f.post('token=a',f.answer(memberIdB))).json().error.code,'COMS_ACCOUNT_CHANGED');
  const context=await f.get('token=a; refreshToken=private; other=value');
  assert.equal(context.statusCode,200,context.body);
  assert.deepEqual(context.json().access.identity,{provider:'coms',name:'로그인 회원 A',memberId:memberIdA});
  assert.deepEqual(f.seen.at(-1),{cookie:'token=a',authorization:undefined});
  assert.equal(context.json().submission,null);
  const payload=f.answer(memberIdA);
  const first=await f.post('token=a',payload);
  assert.equal(first.statusCode,200,first.body);
  assert.equal(first.json().editToken,undefined);
  assert.equal(first.json().submission.name,'로그인 회원 A');
  assert.equal(first.json().submission.verification,'linked');
  assert.deepEqual((await f.post('token=a',payload)).json(),first.json());
  assert.equal((await f.get('token=b')).json().submission,null);
  assert.equal((await f.post('token=b',f.answer(memberIdA))).json().error.code,'COMS_ACCOUNT_CHANGED');
  const other=await f.post('token=b',f.answer(memberIdB));
  assert.equal(other.statusCode,200,other.body);
  assert.equal(other.json().submission.name,'로그인 회원 B');
  assert.equal((await f.post('token=b',{...payload,identityMemberId:memberIdB})).json().error.code,'OPERATION_CONFLICT');
  assert.equal((await f.get('token=a')).json().submission.id,first.json().submission.id);
  assert.equal((await f.get('token=b')).json().submission.id,other.json().submission.id);
  const edit=await f.post('token=a',f.answer(memberIdA,{expectedVersion:1,rsvp:'declined'}));
  assert.equal(edit.statusCode,200,edit.body);
  assert.equal(edit.json().submission.id,first.json().submission.id);
  assert.equal(edit.json().submission.version,2);
  const all=await f.app.inject({url:`/api/admin/events/${f.e.id}/responses`,headers:admin});
  assert.deepEqual(all.json().submissions.map((row:{memberId:string})=>row.memberId).sort(),[memberIdA,memberIdB].sort());
});

test('COMS common links fail closed for upstream, cookie and roster failures',async t=>{
  const f=await fixture(t);
  assert.equal((await f.post('',f.answer(memberIdA))).json().error.code,'COMS_LOGIN_REQUIRED');
  for(const cookie of ['token=expired','token=a; token=b','token=bad value'])
    assert.equal((await f.get(cookie)).json().error.code,'COMS_LOGIN_REQUIRED',cookie);
  for(const cookie of ['token=blocked','token=outsider'])
    assert.equal((await f.get(cookie)).json().error.code,'COMS_MEMBER_REQUIRED',cookie);
  for(const cookie of ['token=unavailable','token=redirect','token=malformed'])
    assert.equal((await f.get(cookie)).json().error.code,'COMS_AUTH_UNAVAILABLE',cookie);
  writeFileSync(f.rosterPath,'{bad');
  assert.equal((await f.get('token=a')).json().error.code,'ROSTER_UNAVAILABLE');
  assert.equal((await f.post('token=a',f.answer(memberIdA))).statusCode,503);
});

test('COMS auth timeout fails closed and personal links keep their existing contract',async t=>{
  const f=await fixture(t);
  const timedOut=await f.get('token=slow');
  f.releaseSlow();
  assert.equal(timedOut.statusCode,503,timedOut.body);
  assert.equal(timedOut.json().error.code,'COMS_AUTH_UNAVAILABLE');
  const personal=await f.app.inject({method:'POST',url:`/api/admin/events/${f.e.id}/links`,headers:admin,payload:{operationId:randomUUID(),kind:'personal',memberId:randomUUID(),expiresAt:'2030-01-01T00:00:00Z'}});
  assert.equal(personal.statusCode,200,personal.body);
  const context=await f.app.inject({url:'/api/respond',headers:{authorization:`Bearer ${personal.json().token}`}});
  assert.equal(context.statusCode,200,context.body);
  assert.equal(context.json().access.identity,undefined);
});

test('COMS identity resolution rechecks revocation after the upstream request',async t=>{
  const f=await fixture(t);
  const pending=f.post('token=slow',f.answer(memberIdA));
  while(!f.seen.some(entry=>entry.cookie==='token=slow'))await new Promise(resolve=>setTimeout(resolve,5));
  const revoked=await f.app.inject({method:'POST',url:`/api/admin/links/${f.link.linkId}/revoke`,headers:admin,payload:{operationId:randomUUID()}});
  assert.equal(revoked.statusCode,200,revoked.body);
  f.releaseSlow();
  const result=await pending;
  assert.equal(result.statusCode,403,result.body);
  assert.equal((await f.app.inject({url:`/api/admin/events/${f.e.id}/responses`,headers:admin})).json().submissions.length,0);
});

test('member cancellation blocks COMS common access and cached replies until an explicit personal regrant',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'companion-coms-revoke-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const databasePath=join(dir,'response.sqlite'),f=await fixture(t,databasePath);
  const payload=f.answer(memberIdA);
  const saved=await f.post('token=a',payload);
  assert.equal(saved.statusCode,200,saved.body);
  const revoke=await f.app.inject({method:'POST',url:`/api/admin/events/${f.e.id}/links/revoke-member`,headers:admin,payload:{operationId:randomUUID(),memberId:memberIdA}});
  assert.equal(revoke.statusCode,200,revoke.body);
  assert.equal(revoke.json().revokedCount,0);
  assert.equal((await f.get('token=a')).json().error.code,'MEMBER_REVOKED');
  assert.equal((await f.post('token=a',payload)).json().error.code,'MEMBER_REVOKED');
  assert.equal((await f.post('token=a',f.answer(memberIdA))).json().error.code,'MEMBER_REVOKED');
  assert.equal((await f.get('token=b')).statusCode,200);
  assert.equal((await f.post('token=b',f.answer(memberIdB))).statusCode,200);
  const freshCommon=await f.app.inject({method:'POST',url:`/api/admin/events/${f.e.id}/links`,headers:admin,payload:{operationId:randomUUID(),kind:'common',expiresAt:'2030-01-01T00:00:00Z'}});
  assert.equal(freshCommon.statusCode,200,freshCommon.body);
  assert.equal((await f.app.inject({url:'/api/respond',headers:{authorization:`Bearer ${freshCommon.json().token}`,cookie:'token=a'}})).json().error.code,'MEMBER_REVOKED');
  const anotherEvent=event();
  assert.equal((await f.app.inject({method:'POST',url:'/api/admin/events',headers:admin,payload:{operationId:randomUUID(),expectedVersion:0,event:anotherEvent}})).statusCode,200);
  const anotherCommon=await f.app.inject({method:'POST',url:`/api/admin/events/${anotherEvent.id}/links`,headers:admin,payload:{operationId:randomUUID(),kind:'common',expiresAt:'2030-01-01T00:00:00Z'}});
  assert.equal(anotherCommon.statusCode,200,anotherCommon.body);
  assert.equal((await f.app.inject({url:'/api/respond',headers:{authorization:`Bearer ${anotherCommon.json().token}`,cookie:'token=a'}})).statusCode,200);
  await f.close();

  const restarted=buildApp({adminToken,encryptionKey,databasePath,publicBaseUrl:'https://coms.kw.ac.kr/companion',rosterPath:f.rosterPath,comsAuthUrl:f.comsAuthUrl});
  t.after(()=>restarted.close());
  assert.equal((await restarted.inject({url:'/api/respond',headers:f.headers('token=a')})).json().error.code,'MEMBER_REVOKED');
  assert.equal((await restarted.inject({url:'/api/respond',headers:f.headers('token=b')})).statusCode,200);
  const personalPayload={operationId:randomUUID(),kind:'personal',memberId:memberIdA,expiresAt:'2030-01-01T00:00:00Z'};
  const personal=await restarted.inject({method:'POST',url:`/api/admin/events/${f.e.id}/links`,headers:admin,payload:personalPayload});
  assert.equal(personal.statusCode,200,personal.body);
  const restored=await restarted.inject({url:'/api/respond',headers:f.headers('token=a')});
  assert.equal(restored.statusCode,200,restored.body);
  assert.equal(restored.json().submission.id,saved.json().submission.id);
  assert.equal((await restarted.inject({method:'POST',url:`/api/admin/events/${f.e.id}/links/revoke-member`,headers:admin,payload:{operationId:randomUUID(),memberId:memberIdA}})).statusCode,200);
  assert.deepEqual((await restarted.inject({method:'POST',url:`/api/admin/events/${f.e.id}/links`,headers:admin,payload:personalPayload})).json(),personal.json());
  assert.equal((await restarted.inject({url:'/api/respond',headers:f.headers('token=a')})).json().error.code,'MEMBER_REVOKED');
});

test('COMS authentication URL accepts only the exact public HTTPS or loopback endpoint',()=>{
  const base={adminToken,encryptionKey,databasePath:':memory:',publicBaseUrl:'https://coms.kw.ac.kr/companion'};
  for(const comsAuthUrl of [
    'https://elsewhere.invalid/api/companion/me','http://elsewhere.invalid/api/companion/me',
    'https://coms.kw.ac.kr/api/companion/me?x=1','https://coms.kw.ac.kr/api/companion/me#x',
    'http://127.0.0.1:8080/api/companion/../companion/me',
    'http://user:pass@127.0.0.1:8080/api/companion/me'
  ])assert.throws(()=>buildApp({...base,comsAuthUrl}),undefined,comsAuthUrl);
  const app=buildApp({...base,comsAuthUrl:'https://coms.kw.ac.kr/api/companion/me'});
  return app.close();
});
