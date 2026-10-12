import {ApiError} from './store.ts';

export function invalid(message:string):never {throw new ApiError(400,'INVALID_INPUT',message);}
export function object(value:unknown):Record<string,unknown> {
  if(!value||typeof value!=='object'||Array.isArray(value))invalid('입력 형식을 확인해 주세요.');
  return value as Record<string,unknown>;
}
export function string(value:unknown,label:string,max=200,empty=false):string {
  if(typeof value!=='string'||value.length>max||(!empty&&!value.trim()))invalid(`${label}을(를) 확인해 주세요.`);
  return value.trim();
}
export function uuid(value:unknown):string {
  const id=string(value,'고유 ID',36);
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id))invalid('고유 ID 형식을 확인해 주세요.');
  return id;
}
export function integer(value:unknown,label:string,min=0,max=1_000_000_000):number {
  if(typeof value!=='number'||!Number.isSafeInteger(value)||value<min||value>max)invalid(`${label}은(는) ${min} 이상 정수여야 해요.`);
  return value;
}
export function bool(value:unknown):boolean {if(typeof value!=='boolean')invalid('선택값을 확인해 주세요.');return value;}
export function date(value:unknown,nullable=false):string|null {
  if(nullable&&value===null)return null;
  const text=string(value,'일시',40);
  if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})$/.test(text)||!Number.isFinite(Date.parse(text)))invalid('시간대가 포함된 일시가 필요해요.');
  return new Date(text).toISOString();
}
export function choices(value:unknown,max=20):string[] {
  if(!Array.isArray(value)||value.length>max)invalid('선택 항목 수를 확인해 주세요.');
  const values=value.map(v=>string(v,'선택 항목',80));
  if(new Set(values).size!==values.length)invalid('중복 선택 항목이 있어요.');
  return values;
}
