import Fastify, {type FastifyRequest} from 'fastify';
import {randomBytes,randomUUID,timingSafeEqual} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {ApiError,Store,hash,type SqlRow} from './store.ts';
import {object,string,uuid,integer,bool,date,choices,invalid} from './validation.ts';

export type EventView={id:string;clubName:string;title:string;startsAt:string|null;location:string;feeAmount:number;capacity:number|null;deadline:string|null;notice:string;cancelled:boolean;version:number;pollOptions:{id:string;label:string}[];pollDeadline:string|null};
type Submission={id:string;eventId:string;name:string;rsvp:'attending'|'declined'|'undecided';noticeAcknowledged:boolean;selectedOptions:string[];version:number;eventVersion:number;updatedAt:string;verification:'pending'|'linked'};
type Payment={id:string;amount:number;depositorName:string;depositedAt:string;status:'pending'|'confirmed'|'rejected';createdAt:string};
type Review={reviewId:string;ledgerEntryId:string;chargeId:string;confirmedAmount:number;ownerId:string;reason:string;state:'reserved'|'confirmed'};
type ReviewedPayment=Payment&{review?:Review;reviewReason?:string};
type Link=SqlRow&{id:string;event_id:string;kind:'common'|'personal'|'submission';member_id:string|null;submission_id:string|null;expires_at:string;revoked:number;scopes:string};
type State='open'|'closed'|'cancelled'|'expired'|'revoked';
type Options={adminToken:string;encryptionKey:string;databasePath:string;publicBaseUrl:string;webDist?:string;now?:()=>Date;rateLimit?:number};
const scopes=['rsvp','notice','vote','payment'];
const parse=<T>(row:SqlRow):T=>JSON.parse(String(row.payload)) as T;
const messages:Record<State,string>={open:'응답을 제출하거나 수정할 수 있어요.',closed:'응답 마감이 지나 수정할 수 없어요.',cancelled:'취소된 행사예요.',expired:'링크 유효기간이 지났어요.',revoked:'취소된 초대 링크예요.'};

function validateEvent(value:unknown):EventView {
  const e=object(value),options=e.pollOptions;
  if(!Array.isArray(options)||options.length>20)invalid('일정 후보는 20개 이하여야 해요.');
  const pollOptions=options.map(o=>{const v=object(o);return {id:string(v.id,'후보 ID',80),label:string(v.label,'후보 날짜',200)};});
  if(new Set(pollOptions.map(v=>v.id)).size!==pollOptions.length)invalid('일정 후보 ID가 중복됐어요.');
  return {id:uuid(e.id),clubName:string(e.clubName,'동아리명'),title:string(e.title,'행사명'),startsAt:date(e.startsAt,true),location:string(e.location,'장소',500,true),feeAmount:integer(e.feeAmount,'참가비'),capacity:e.capacity===null?null:integer(e.capacity,'정원',1,100000),deadline:date(e.deadline,true),notice:string(e.notice,'안내사항',10000,true),cancelled:bool(e.cancelled),version:integer(e.version,'행사 버전'),pollOptions,pollDeadline:date(e.pollDeadline,true)};
}

export function buildApp(options:Options) {
  if(options.adminToken.length<32||options.adminToken.includes('replace_with'))throw new Error('ADMIN_TOKEN must be a random secret of at least 32 characters.');
  if(!/^[a-f0-9]{64}$/i.test(options.encryptionKey))throw new Error('SERVER_ENCRYPTION_KEY must contain 64 hex characters.');
  const base=new URL(options.publicBaseUrl);
  if(base.username||base.password||base.search||base.hash||base.pathname!=='/')throw new Error('PUBLIC_BASE_URL must be an origin without credentials.');
  if(base.protocol!=='https:'&&!(base.protocol==='http:'&&['localhost','127.0.0.1','10.0.2.2','[::1]'].includes(base.hostname)))throw new Error('Use HTTPS outside local development.');
  const store=new Store(options.databasePath,options.encryptionKey),now=options.now??(()=>new Date());
  const app=Fastify({logger:false,bodyLimit:64*1024,trustProxy:false});
  const limits=new Map<string,{since:number;count:number}>();
  app.addHook('onClose',async()=>store.close());
  app.addHook('onRequest',async(req,reply)=>{
    reply.header('Cache-Control','no-store').header('Referrer-Policy','no-referrer').header('X-Content-Type-Options','nosniff').header('X-Frame-Options','DENY');
    reply.header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    if(req.method!=='GET'&&req.method!=='HEAD'&&req.headers.origin&&req.headers.origin!==base.origin)throw new ApiError(403,'SCOPE','허용되지 않은 출처의 요청이에요.');
    const time=now().getTime(),previous=limits.get(req.ip);
    const current=previous&&time-previous.since<60000?previous:{since:time,count:0};
    current.count++;limits.set(req.ip,current);
    if(limits.size>5000)limits.delete(limits.keys().next().value!);
    if(current.count>(options.rateLimit??120)){reply.header('Retry-After','60');throw new ApiError(429,'RATE_LIMITED','요청이 많아요. 잠시 뒤 다시 시도해 주세요.');}
  });
  app.setErrorHandler((error,_req,reply)=>{
    if(error instanceof ApiError)return reply.code(error.status).send({error:{code:error.code,message:error.message},...(error.current?{current:error.current}:{})});
    if(error&&typeof error==='object'&&'statusCode' in error&&typeof error.statusCode==='number'&&error.statusCode<500)return reply.code(error.statusCode).send({error:{code:'INVALID_INPUT',message:'입력 형식이나 크기를 확인해 주세요.'}});
    return reply.code(500).send({error:{code:'SAVE_FAILED',message:'저장 결과를 확인하지 못했어요. 작성 내용을 유지하고 다시 시도해 주세요.'}});
  });
  function token(req:FastifyRequest):string {
    const match=/^Bearer ([^\s]+)$/.exec(req.headers.authorization??'');
    if(!match?.[1])throw new ApiError(401,'AUTH','유효한 링크나 운영자 인증이 필요해요.');
    return match[1];
  }
  const adminOnly={onRequest:async(req:FastifyRequest)=>{
    const provided=Buffer.from(token(req)),expected=Buffer.from(options.adminToken);
    if(provided.length!==expected.length||!timingSafeEqual(provided,expected))throw new ApiError(401,'AUTH','운영자 인증을 확인해 주세요.');
  }};
  function getEvent(id:string):EventView {
    const row=store.row('SELECT * FROM events WHERE id=?',id);
    if(!row)throw new ApiError(404,'INVALID_EVENT','행사를 찾을 수 없어요.');return parse<EventView>(row);
  }
  function getLink(req:FastifyRequest):Link {
    const value=token(req);
    if(value.length>200)throw new ApiError(404,'INVALID_LINK','링크를 찾을 수 없어요.');
    const row=store.row('SELECT * FROM links WHERE token_hash=?',hash(value));
    if(!row)throw new ApiError(404,'INVALID_LINK','링크를 찾을 수 없어요.');return row as Link;
  }
  function state(link:Link,e:EventView):State {
    if(link.revoked)return 'revoked';
    if(Date.parse(link.expires_at)<=now().getTime())return 'expired';
    if(e.cancelled)return 'cancelled';
    if(e.deadline&&Date.parse(e.deadline)<=now().getTime())return 'closed';return 'open';
  }
  function ensureOpen(link:Link,e:EventView) {
    const value=state(link,e);
    if(value!=='open')throw new ApiError(value==='expired'?410:value==='revoked'?403:422,value.toUpperCase(),messages[value]);
  }
  function ensureLive(link:Link,e:EventView) {
    const value=state(link,e);
    if(value==='expired'||value==='revoked')throw new ApiError(value==='expired'?410:403,value.toUpperCase(),messages[value]);
  }
  function own(link:Link):Submission|null {
    const row=link.submission_id?store.row('SELECT * FROM submissions WHERE id=? AND event_id=?',link.submission_id,link.event_id):link.member_id?store.row('SELECT * FROM submissions WHERE event_id=? AND member_id=?',link.event_id,link.member_id):undefined;
    return row?parse<Submission>(row):null;
  }
  function op(req:FastifyRequest,owner:string,fn:()=>unknown) {
    const body=object(req.body);const id=uuid(body.operationId);
    return store.operation(id,owner,{url:req.url,body},now().toISOString(),fn);
  }
  function answer(body:Record<string,unknown>,e:EventView,current:Submission|null,allowed:string[],kind:'pending'|'linked'):Submission {
    if(integer(body.eventVersion,'행사 버전')!==e.version)throw new ApiError(409,'EVENT_CONFLICT','행사 안내가 변경됐어요. 최신 내용을 확인해 주세요.');
    if(integer(body.expectedVersion,'응답 버전')!==(current?.version??0))throw new ApiError(409,'CONFLICT','다른 화면에서 응답이 변경됐어요. 최신 응답을 검토해 주세요.',current);
    const rsvp=string(body.rsvp,'참석 응답');
    if(!['attending','declined','undecided'].includes(rsvp))invalid('참석 응답을 선택해 주세요.');
    const acknowledged=bool(body.noticeAcknowledged),selected=choices(body.selectedOptions);
    if(!allowed.includes('rsvp')&&rsvp!==(current?.rsvp??'undecided'))throw new ApiError(403,'SCOPE','이 링크로 참석 응답을 변경할 수 없어요.');
    if(!allowed.includes('notice')&&acknowledged!==(current?.noticeAcknowledged??false))throw new ApiError(403,'SCOPE','이 링크로 안내 확인을 변경할 수 없어요.');
    const changedVotes=JSON.stringify([...selected].sort())!==JSON.stringify([...(current?.selectedOptions??[])].sort());
    if(changedVotes&&!allowed.includes('vote'))throw new ApiError(403,'SCOPE','이 링크로 일정 투표를 변경할 수 없어요.');
    if(changedVotes&&e.pollDeadline&&Date.parse(e.pollDeadline)<=now().getTime())throw new ApiError(422,'POLL_CLOSED','일정 투표 마감이 지났어요.');
    if(changedVotes&&selected.some(id=>!e.pollOptions.some(o=>o.id===id)))invalid('현재 행사에 없는 일정 후보가 포함됐어요.');
    if(rsvp==='attending'&&current?.rsvp!=='attending'&&e.capacity!==null){
      const count=store.rows('SELECT * FROM submissions WHERE event_id=?',e.id).map(r=>parse<Submission>(r)).filter(s=>s.rsvp==='attending').length;
      if(count>=e.capacity)throw new ApiError(409,'CAPACITY','정원이 찼어요. 참석으로 제출할 수 없어요.');
    }
    return {id:current?.id??randomUUID(),eventId:e.id,name:string(body.name,'이름',100),rsvp:rsvp as Submission['rsvp'],noticeAcknowledged:acknowledged,selectedOptions:selected,version:(current?.version??0)+1,eventVersion:e.version,updatedAt:now().toISOString(),verification:kind};
  }
  function saveSubmission(value:Submission,memberId:string|null,current:Submission|null,actor:string) {
    const recorded={...value,source:actor.startsWith('admin')?'operator':'member'};
    store.run('INSERT INTO submissions(id,event_id,member_id,payload) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET member_id=excluded.member_id,payload=excluded.payload',value.id,value.eventId,memberId,JSON.stringify(recorded));
    store.audit(actor,'response',value.id,current,recorded,now().toISOString());
    store.change(value.eventId,'submission',value.id,memberId,recorded);
  }
  function newLink(e:EventView,kind:Link['kind'],memberId:string|null,expiresAt:string,allowed:string[],submissionId:string|null=null,parentLinkId:string|null=null) {
    const id=randomUUID(),value=randomBytes(32).toString('base64url');
    store.run('INSERT INTO links(id,token_hash,event_id,kind,member_id,submission_id,scopes,expires_at,parent_link_id) VALUES(?,?,?,?,?,?,?,?,?)',id,hash(value),e.id,kind,memberId,submissionId,JSON.stringify(allowed),expiresAt,parentLinkId);
    return {linkId:id,token:value,url:`${base.origin}/respond#${value}`};
  }
  function revokeLink(id:string) {
    store.run('UPDATE links SET revoked=1 WHERE id=? OR parent_link_id=?',id,id);
    store.audit('admin','revoke-link',id,null,{revoked:true},now().toISOString());
    return {revoked:true};
  }

  app.get('/api/health',()=>({status:'ok',mode:'templates-no-llm'}));
  app.get('/api/respond',(req)=>{
    const link=getLink(req),e=getEvent(link.event_id),accessState=state(link,e);
    const privateReadable=['open','closed'].includes(accessState),allowed=JSON.parse(link.scopes) as string[];
    return {event:e,access:{kind:link.kind,state:accessState,scopes:allowed,canPay:accessState==='open'&&link.kind==='personal'&&allowed.includes('payment'),message:messages[accessState]},submission:privateReadable?own(link):null,paymentRequests:privateReadable&&link.kind==='personal'&&allowed.includes('payment')?store.rows('SELECT * FROM payments WHERE event_id=? AND member_id=? ORDER BY rowid',e.id,link.member_id).map(r=>{
      const p=parse<ReviewedPayment>(r);return {id:p.id,amount:p.amount,depositorName:p.depositorName,depositedAt:p.depositedAt,status:p.status,createdAt:p.createdAt,...(p.status==='confirmed'&&p.review?.state==='confirmed'?{confirmedAmount:p.review.confirmedAmount}:{})};
    }):[]};
  });
  app.post('/api/respond',(req)=>{
    const link=getLink(req),e=getEvent(link.event_id);ensureLive(link,e);
    return op(req,String(link.token_hash),()=>{
      ensureOpen(link,e);
      const current=own(link),allowed=JSON.parse(link.scopes) as string[];
      if(!allowed.some(v=>['rsvp','notice','vote'].includes(v)))throw new ApiError(403,'SCOPE','이 링크는 입금 확인 요청만 허용해요.');
      const memberId=link.member_id??(current?store.row('SELECT member_id FROM submissions WHERE id=?',current.id)?.member_id as string|null:null);
      const value=answer(object(req.body),e,current,allowed,memberId?'linked':'pending');
      saveSubmission(value,memberId??null,current,`member:${link.kind}`);
      if(link.kind==='common'){
        const edit=newLink(e,'submission',null,link.expires_at,allowed,value.id,link.id);
        return {submission:value,editToken:edit.token};
      }
      return {submission:value};
    });
  });
  app.post('/api/respond/payments',(req)=>{
    const link=getLink(req),e=getEvent(link.event_id);ensureLive(link,e);
    if(link.kind!=='personal'||!link.member_id||!(JSON.parse(link.scopes) as string[]).includes('payment'))throw new ApiError(403,'SCOPE','개인 입금 확인 링크가 필요해요.');
    return op(req,String(link.token_hash),()=>{
      ensureOpen(link,e);
      const body=object(req.body);
      if(integer(body.eventVersion,'행사 버전')!==e.version)throw new ApiError(409,'EVENT_CONFLICT','변경된 행사 내용을 먼저 확인해 주세요.');
      const depositedAt=date(body.depositedAt)!;
      if(Date.parse(depositedAt)>now().getTime()+5*60*1000)invalid('입금 시각은 현재보다 미래일 수 없어요.');
      const payment:Payment={id:randomUUID(),amount:integer(body.amount,'입금액',1),depositorName:string(body.depositorName,'입금자명',100),depositedAt,status:'pending',createdAt:now().toISOString()};
      store.run('INSERT INTO payments(id,event_id,member_id,payload) VALUES(?,?,?,?)',payment.id,e.id,link.member_id,JSON.stringify(payment));
      store.audit('member','payment-claim',payment.id,null,payment,now().toISOString());store.change(e.id,'payment',payment.id,link.member_id,payment);return {request:payment};
    });
  });
  app.post('/api/admin/events',{...adminOnly,bodyLimit:512*1024},(req)=>op(req,'admin',()=>{
    const body=object(req.body),e=validateEvent(body.event),row=store.row('SELECT * FROM events WHERE id=?',e.id),previous=row?parse<EventView>(row):null;
    if(integer(body.expectedVersion,'기준 버전')!==(previous?.version??0))throw new ApiError(409,'EVENT_CONFLICT','서버 행사 버전이 달라요. 최신 내용을 확인해 주세요.',previous);
    if(e.version!==(previous?.version??0))throw new ApiError(409,'EVENT_CONFLICT','행사 버전이 요청 기준과 달라요.',previous);
    const value={...e,version:(previous?.version??0)+1};
    const initial=body.initialResponses??[];
    if(!Array.isArray(initial)||initial.length>500)invalid('최초 수동 응답은 500건 이하여야 해요.');
    if(previous&&initial.length)throw new ApiError(409,'EVENT_CONFLICT','최초 게시 후에는 버전이 있는 응답 변경으로 보내 주세요.');
    store.run('INSERT INTO events VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload',value.id,JSON.stringify(value));
    store.audit('admin','event',e.id,previous,value,now().toISOString());store.change(e.id,'event',e.id,null,value);
    const ids=new Set<string>(),members=new Set<string>(),accepted=[];
    for(const input of initial){
      const manual=object(input),id=uuid(manual.id),memberId=uuid(manual.memberId),sourceOperationId=uuid(manual.operationId);
      if(ids.has(id)||members.has(memberId)||store.row('SELECT id FROM submissions WHERE id=?',id))invalid('최초 응답 ID 또는 회원이 중복됐어요.');
      ids.add(id);members.add(memberId);
      const response={...answer({...manual,expectedVersion:0,eventVersion:value.version},value,null,scopes,'linked'),id,source:'operator',sourceOperationId};
      saveSubmission(response,memberId,null,'admin-import');accepted.push({...response,memberId});
    }
    return {event:value,initialResponses:accepted};
  }));
  app.post<{Params:{id:string}}>('/api/admin/events/:id/links',adminOnly,(req)=>op(req,'admin',()=>{
    const e=getEvent(uuid(req.params.id)),body=object(req.body),kind=string(body.kind,'링크 종류');
    if(kind!=='common'&&kind!=='personal')invalid('링크 종류를 확인해 주세요.');
    const memberId=kind==='personal'?uuid(body.memberId):null,expiresAt=date(body.expiresAt)!;
    if(Date.parse(expiresAt)<=now().getTime())invalid('링크 만료 시각이 현재보다 늦어야 해요.');
    const allowed=body.scopes===undefined?(kind==='personal'?scopes:scopes.filter(v=>v!=='payment')):choices(body.scopes,4);
    if(!allowed.length||allowed.some(s=>!scopes.includes(s))||(kind==='common'&&allowed.includes('payment')))invalid('링크 권한 범위를 확인해 주세요.');
    return newLink(e,kind,memberId,expiresAt,allowed);
  }));
  app.post<{Params:{id:string}}>('/api/admin/links/:id/revoke',adminOnly,(req)=>op(req,'admin',()=>{
    const id=uuid(req.params.id),row=store.row('SELECT id FROM links WHERE id=?',id);
    if(!row)throw new ApiError(404,'INVALID_LINK','링크를 찾을 수 없어요.');
    return revokeLink(id);
  }));
  app.post<{Params:{id:string}}>('/api/admin/events/:id/links/revoke',adminOnly,(req)=>op(req,'admin',()=>{
    const eventId=uuid(req.params.id),body=object(req.body),issuedOperationId=uuid(body.issuedOperationId);
    const issued=store.row("SELECT receipt FROM operations WHERE id=? AND owner='admin'",issuedOperationId);
    if(!issued)throw new ApiError(404,'INVALID_LINK','링크 발급 기록을 찾을 수 없어요.');
    const receipt=store.decrypt<{linkId?:string}>(String(issued.receipt));
    const row=typeof receipt.linkId==='string'?store.row('SELECT id FROM links WHERE id=? AND event_id=?',receipt.linkId,eventId):undefined;
    if(!row)throw new ApiError(404,'INVALID_LINK','이 행사에 발급된 링크가 아니에요.');
    return revokeLink(String(row.id));
  }));
  app.post<{Params:{id:string}}>('/api/admin/events/:id/links/revoke-member',adminOnly,(req)=>op(req,'admin',()=>{
    const eventId=getEvent(uuid(req.params.id)).id,memberId=uuid(object(req.body).memberId);
    const links=store.rows("SELECT id FROM links WHERE event_id=? AND revoked=0 AND ((kind='personal' AND member_id=?) OR (kind='submission' AND submission_id IN (SELECT id FROM submissions WHERE event_id=? AND member_id=?)))",eventId,memberId,eventId,memberId);
    for(const link of links)revokeLink(String(link.id));
    return {revokedCount:links.length};
  }));
  app.get<{Params:{id:string}}>('/api/admin/events/:id/responses',adminOnly,(req)=>{
    const e=getEvent(uuid(req.params.id));
    return {event:e,submissions:store.rows('SELECT * FROM submissions WHERE event_id=? ORDER BY rowid',e.id).map(r=>({...parse<Submission>(r),memberId:r.member_id})),paymentRequests:store.rows('SELECT * FROM payments WHERE event_id=? ORDER BY rowid',e.id).map(r=>({...parse<Payment>(r),memberId:r.member_id}))};
  });
  app.get<{Querystring:{cursor?:string}}>('/api/admin/changes',adminOnly,(req)=>{
    const cursor=integer(Number(req.query.cursor??0),'동기화 위치',0,Number.MAX_SAFE_INTEGER);
    const maximum=Number(store.row('SELECT COALESCE(MAX(sequence),0) AS value FROM changes')?.value??0);
    if(cursor>maximum)throw new ApiError(409,'CURSOR_RESET','서버 동기화 기록이 변경됐어요. 처음부터 다시 확인해 주세요.');
    const rows=store.rows('SELECT * FROM changes WHERE sequence>? ORDER BY sequence LIMIT 101',cursor),batch=rows.slice(0,100);
    return {changes:batch.map(row=>({sequence:Number(row.sequence),eventId:row.event_id,kind:row.kind,entityId:row.entity_id,memberId:row.member_id,payload:JSON.parse(String(row.payload))})),nextCursor:batch.length?Number(batch.at(-1)!.sequence):cursor,hasMore:rows.length>100};
  });
  app.post<{Params:{id:string}}>('/api/admin/events/:id/submissions',adminOnly,(req)=>op(req,'admin',()=>{
    const e=getEvent(uuid(req.params.id)),body=object(req.body),id=uuid(body.id),memberId=uuid(body.memberId);
    if(e.cancelled)throw new ApiError(422,'CANCELLED',messages.cancelled);
    const existing=store.row('SELECT * FROM submissions WHERE event_id=? AND member_id=?',e.id,memberId);
    if(existing)throw new ApiError(409,'CONFLICT','회원이 이미 응답했어요. 양쪽 내용을 비교해 주세요.',parse<Submission>(existing));
    if(store.row('SELECT id FROM submissions WHERE id=?',id))invalid('다른 응답에서 사용 중인 ID예요.');
    const value={...answer(body,e,null,scopes,'linked'),id,source:'operator'};
    saveSubmission(value,memberId,null,'admin');return {submission:value};
  }));
  app.post<{Params:{id:string;submissionId:string}}>('/api/admin/events/:id/submissions/:submissionId/link',adminOnly,(req)=>op(req,'admin',()=>{
    const e=getEvent(uuid(req.params.id)),id=uuid(req.params.submissionId),body=object(req.body),memberId=uuid(body.memberId);
    const row=store.row('SELECT * FROM submissions WHERE id=? AND event_id=?',id,e.id);
    if(!row)throw new ApiError(404,'INVALID_SUBMISSION','응답을 찾을 수 없어요.');
    const previous=parse<Submission>(row);
    if(integer(body.expectedVersion,'응답 버전')!==previous.version)throw new ApiError(409,'CONFLICT','응답이 변경됐어요.',previous);
    if(row.member_id&&row.member_id!==memberId)throw new ApiError(409,'CONFLICT','이미 다른 회원에 연결된 응답이에요.',previous);
    if(store.row('SELECT id FROM submissions WHERE event_id=? AND member_id=? AND id<>?',e.id,memberId,id))throw new ApiError(409,'CONFLICT','회원에게 이미 다른 응답이 있어요. 덮어쓸 수 없어요.');
    const value={...previous,verification:'linked' as const,version:previous.version+1,updatedAt:now().toISOString()};
    saveSubmission(value,memberId,previous,'admin-link');return {submission:value};
  }));
  app.post<{Params:{id:string;submissionId:string}}>('/api/admin/events/:id/submissions/:submissionId',adminOnly,(req)=>op(req,'admin',()=>{
    const e=getEvent(uuid(req.params.id)),row=store.row('SELECT * FROM submissions WHERE id=? AND event_id=?',uuid(req.params.submissionId),e.id);
    if(!row)throw new ApiError(404,'INVALID_SUBMISSION','응답을 찾을 수 없어요.');
    if(e.cancelled)throw new ApiError(422,'CANCELLED',messages.cancelled);
    const previous=parse<Submission>(row),value=answer(object(req.body),e,previous,scopes,previous.verification);
    saveSubmission(value,row.member_id as string|null,previous,'admin');return {submission:value};
  }));
  app.post<{Params:{id:string;requestId:string}}>('/api/admin/events/:id/payments/:requestId/review',adminOnly,(req)=>op(req,'admin',()=>{
    const e=getEvent(uuid(req.params.id)),id=uuid(req.params.requestId),body=object(req.body),status=string(body.status,'검토 결과');
    if(status!=='confirmed'&&status!=='rejected')invalid('확인 또는 반려를 선택해 주세요.');
    const ledgerId=status==='confirmed'?uuid(body.ledgerEntryId):null,row=store.row('SELECT * FROM payments WHERE id=? AND event_id=?',id,e.id);
    if(!row)throw new ApiError(404,'INVALID_REQUEST','입금 확인 요청을 찾을 수 없어요.');
    const previous=parse<ReviewedPayment>(row);
    if(previous.status!=='pending')throw new ApiError(409,'CONFLICT','이미 검토한 요청이에요. 원장 정정은 앱에서 처리해 주세요.');
    if(previous.review)throw new ApiError(409,'REVIEW_CONFLICT','검토 예약이 있어요. 원래 확인 작업을 마무리해 주세요.',previous);
    if(status==='confirmed')throw new ApiError(409,'REVIEW_PREPARE_REQUIRED','납부 원장 연결을 먼저 예약한 뒤 확인을 완료해 주세요.');
    if(ledgerId&&store.row('SELECT id FROM payments WHERE ledger_entry_id=?',ledgerId))throw new ApiError(409,'CONFLICT','이 납부 기록은 다른 요청과 연결됐어요.');
    const value={...previous,status:status as Payment['status'],reviewReason:string(body.reason??'운영자 반려','사유',500)};
    store.run('UPDATE payments SET payload=?,ledger_entry_id=? WHERE id=?',JSON.stringify(value),ledgerId,id);store.audit('admin','review-payment',id,previous,value,now().toISOString());store.change(e.id,'payment',id,String(row.member_id),value);return {request:value};
  }));
  app.post<{Params:{id:string;requestId:string}}>('/api/admin/events/:id/payments/:requestId/review/prepare',adminOnly,(req)=>op(req,'admin',()=>{
    const eventId=uuid(req.params.id),id=uuid(req.params.requestId),body=object(req.body);getEvent(eventId);
    const proposed:Review={reviewId:uuid(body.reviewId),ledgerEntryId:uuid(body.ledgerEntryId),chargeId:uuid(body.chargeId),confirmedAmount:integer(body.confirmedAmount,'실제 확인 금액',1),ownerId:uuid(body.ownerId),reason:string(body.reason,'확인 사유',500),state:'reserved'};
    const row=store.row('SELECT * FROM payments WHERE id=? AND event_id=?',id,eventId);
    if(!row)throw new ApiError(404,'INVALID_REQUEST','입금 확인 요청을 찾을 수 없어요.');
    const previous=parse<ReviewedPayment>(row);
    if(previous.review){
      const r=previous.review;
      if(r.reviewId===proposed.reviewId&&r.ledgerEntryId===proposed.ledgerEntryId&&r.chargeId===proposed.chargeId&&r.confirmedAmount===proposed.confirmedAmount&&r.ownerId===proposed.ownerId&&r.reason===proposed.reason)return {review:r,request:previous};
      throw new ApiError(409,'REVIEW_CONFLICT','다른 검토가 먼저 예약되거나 완료됐어요. 원장을 중복 반영할 수 없어요.',previous);
    }
    if(previous.status!=='pending')throw new ApiError(409,'REVIEW_CONFLICT','이미 검토한 요청이에요.',previous);
    if(store.row('SELECT id FROM payments WHERE ledger_entry_id=?',proposed.ledgerEntryId))throw new ApiError(409,'REVIEW_CONFLICT','이 원장 기록은 다른 요청과 연결돼 있어요.');
    const value={...previous,review:proposed};
    store.run('UPDATE payments SET payload=?,ledger_entry_id=? WHERE id=?',JSON.stringify(value),proposed.ledgerEntryId,id);
    store.audit('admin','prepare-payment-review',id,previous,value,now().toISOString());store.change(eventId,'payment',id,String(row.member_id),value);return {review:proposed,request:value};
  }));
  app.post<{Params:{id:string;requestId:string}}>('/api/admin/events/:id/payments/:requestId/review/commit',adminOnly,(req)=>op(req,'admin',()=>{
    const eventId=uuid(req.params.id),id=uuid(req.params.requestId),body=object(req.body);getEvent(eventId);
    const reviewId=uuid(body.reviewId),ledgerEntryId=uuid(body.ledgerEntryId),row=store.row('SELECT * FROM payments WHERE id=? AND event_id=?',id,eventId);
    if(!row)throw new ApiError(404,'INVALID_REQUEST','입금 확인 요청을 찾을 수 없어요.');
    const previous=parse<ReviewedPayment>(row),r=previous.review;
    if(!r||r.reviewId!==reviewId||r.ledgerEntryId!==ledgerEntryId)throw new ApiError(409,'REVIEW_CONFLICT','예약한 원장 연결과 달라요. 다시 비교해 주세요.',previous);
    if(previous.status==='confirmed')return {review:r,request:previous};
    if(previous.status!=='pending')throw new ApiError(409,'REVIEW_CONFLICT','검토 상태가 변경됐어요.',previous);
    const review={...r,state:'confirmed' as const},value={...previous,status:'confirmed' as const,review};
    store.run('UPDATE payments SET payload=? WHERE id=?',JSON.stringify(value),id);store.audit('admin','commit-payment-review',id,previous,value,now().toISOString());store.change(eventId,'payment',id,String(row.member_id),value);return {review,request:value};
  }));

  if(options.webDist){
    const root=resolve(options.webDist);
    const index=(_req:unknown,reply:import('fastify').FastifyReply)=>{
      try{return reply.type('text/html; charset=utf-8').send(readFileSync(resolve(root,'index.html')));}
      catch{return reply.code(503).send({error:{code:'WEB_NOT_BUILT',message:'회원 웹 빌드가 필요해요.'}});}
    };
    app.get('/',index);app.get('/respond',index);
    app.get<{Params:{file:string}}>('/assets/:file',(req,reply)=>{
      if(!/^[A-Za-z0-9_.-]+$/.test(req.params.file))throw new ApiError(404,'NOT_FOUND','파일을 찾을 수 없어요.');
      const ext=req.params.file.split('.').pop(),type=ext==='js'?'application/javascript':ext==='css'?'text/css':ext==='svg'?'image/svg+xml':'application/octet-stream';
      try{return reply.type(type).send(readFileSync(resolve(root,'assets',req.params.file)));}
      catch{throw new ApiError(404,'NOT_FOUND','파일을 찾을 수 없어요.');}
    });
  }
  return app;
}
