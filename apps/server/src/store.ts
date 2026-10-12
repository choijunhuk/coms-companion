import {DatabaseSync} from 'node:sqlite';
import {createCipheriv, createDecipheriv, createHash, randomBytes} from 'node:crypto';
import {mkdirSync, chmodSync} from 'node:fs';
import {dirname} from 'node:path';

export class ApiError extends Error {
  status: number;
  code: string;
  current: unknown;
  constructor(status: number, code: string, message: string, current?: unknown) {
    super(message);this.status=status;this.code=code;this.current=current;
  }
}
export const hash=(value:string)=>createHash('sha256').update(value).digest('hex');
export type SqlRow=Record<string,string|number|null>;
const canonical=(value:unknown):string=>{
  if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
  if(value!==null&&typeof value==='object')return '{'+Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>JSON.stringify(k)+':'+canonical(v)).join(',')+'}';
  return JSON.stringify(value)??'null';
};

export class Store {
  db: DatabaseSync;
  key: Buffer;
  constructor(path:string, encryptionKey:string) {
    this.key=Buffer.from(encryptionKey,'hex');
    if(path!==':memory:')mkdirSync(dirname(path),{recursive:true,mode:0o700});
    this.db=new DatabaseSync(path);
    if(path!==':memory:')chmodSync(path,0o600);
    this.db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, payload TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS links(id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL,
        event_id TEXT NOT NULL REFERENCES events(id), kind TEXT NOT NULL, member_id TEXT,
        submission_id TEXT, scopes TEXT NOT NULL, expires_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS submissions(id TEXT PRIMARY KEY, event_id TEXT NOT NULL REFERENCES events(id),
        member_id TEXT, payload TEXT NOT NULL, UNIQUE(event_id,member_id));
      CREATE TABLE IF NOT EXISTS payments(id TEXT PRIMARY KEY, event_id TEXT NOT NULL REFERENCES events(id),
        member_id TEXT NOT NULL, payload TEXT NOT NULL, ledger_entry_id TEXT UNIQUE);
      CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, owner TEXT NOT NULL, fingerprint TEXT NOT NULL,
        receipt TEXT NOT NULL, created_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL,
        action TEXT NOT NULL, entity_id TEXT NOT NULL, before_value TEXT, after_value TEXT, created_at TEXT NOT NULL);
      PRAGMA user_version=1;`);
  }
  row(sql:string,...args:(string|number|null)[]):SqlRow|undefined {
    return this.db.prepare(sql).get(...args) as SqlRow|undefined;
  }
  rows(sql:string,...args:(string|number|null)[]):SqlRow[] {
    return this.db.prepare(sql).all(...args) as SqlRow[];
  }
  run(sql:string,...args:(string|number|null)[]) {return this.db.prepare(sql).run(...args);}
  transaction<T>(fn:()=>T):T {
    this.db.exec('BEGIN IMMEDIATE');
    try {const result=fn();this.db.exec('COMMIT');return result;}
    catch(error){this.db.exec('ROLLBACK');throw error;}
  }
  encrypt(value:unknown):string {
    const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',this.key,iv);
    const encrypted=Buffer.concat([cipher.update(JSON.stringify(value),'utf8'),cipher.final()]);
    return Buffer.concat([iv,cipher.getAuthTag(),encrypted]).toString('base64');
  }
  decrypt<T>(receipt:string):T {
    const data=Buffer.from(receipt,'base64');
    const decipher=createDecipheriv('aes-256-gcm',this.key,data.subarray(0,12));
    decipher.setAuthTag(data.subarray(12,28));
    return JSON.parse(Buffer.concat([decipher.update(data.subarray(28)),decipher.final()]).toString('utf8')) as T;
  }
  operation<T>(id:string,owner:string,request:unknown,now:string,fn:()=>T):T {
    return this.transaction(()=>{
      const fingerprint=hash(canonical(request)),previous=this.row('SELECT * FROM operations WHERE id=?',id);
      if(previous){
        if(previous.owner!==owner||previous.fingerprint!==fingerprint)throw new ApiError(409,'OPERATION_CONFLICT','같은 작업 ID로 다른 내용을 저장할 수 없어요.');
        return this.decrypt<T>(String(previous.receipt));
      }
      const result=fn();
      this.run('INSERT INTO operations VALUES(?,?,?,?,?)',id,owner,fingerprint,this.encrypt(result),now);
      return result;
    });
  }
  audit(actor:string,action:string,id:string,before:unknown,after:unknown,now:string) {
    this.run('INSERT INTO audit(actor,action,entity_id,before_value,after_value,created_at) VALUES(?,?,?,?,?,?)',actor,action,id,before===null?null:JSON.stringify(before),after===null?null:JSON.stringify(after),now);
  }
  close(){this.db.close();}
}
