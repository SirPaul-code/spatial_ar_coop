/** Small pure policy module, shared by the deployed service and its tests. */
export const ROOM_RE = /^[A-Za-z0-9_-]{24}$/;
export const ID_RE = /^[A-Za-z0-9_-]{8,80}$/;
export const MAX_SIGNAL_BYTES = 90_000;
export const MAX_SESSION_SECONDS = 3600;
export const json = (value, status=200) => new Response(JSON.stringify(value), {
  status, headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store',
    'referrer-policy':'no-referrer','x-content-type-options':'nosniff'}
});
export const fail = (code, message, status=400) => json({ok:false,code,message},status);
export function boundedInt(value, fallback, min, max) {
  const n=Number(value); return Number.isInteger(n)&&n>=min&&n<=max?n:fallback;
}
export function cleanName(value) { return String(value??'Helper').replace(/[\u0000-\u001f\u007f]/g,'').slice(0,32).trim()||'Helper'; }
export function token(bytes=24) {
  const data=crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...data)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
}
export async function digest(value) {
  const data=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value));
  return Array.from(new Uint8Array(data),v=>v.toString(16).padStart(2,'0')).join('');
}
export async function sameSecret(value, expected) {
  if(typeof value!=='string'||typeof expected!=='string'||!value||!expected||value.length>256)return false;
  const a=await digest(value),b=await digest(expected);let diff=0;
  for(let i=0;i<a.length;i++)diff|=a.charCodeAt(i)^b.charCodeAt(i);
  return diff===0;
}
export function bearer(request) {
  const header=request.headers.get('authorization')||'';
  if(header.startsWith('Bearer '))return header.slice(7);
  // Browser WebSockets cannot set Authorization. A subprotocol avoids secrets in URL query/logs.
  return (request.headers.get('sec-websocket-protocol')||'').split(',').map(v=>v.trim())
    .find(v=>v.startsWith('cap.'))?.slice(4)||'';
}
export async function readJson(request, limit=4096) {
  if(!request.headers.get('content-type')?.startsWith('application/json'))throw new Error('JSON_REQUIRED');
  const stated=Number(request.headers.get('content-length')||0);
  if(stated>limit)throw new Error('TOO_LARGE');
  const reader=request.body?.getReader();if(!reader)throw new Error('EMPTY_BODY');
  const chunks=[];let size=0;
  try{while(true){const {value,done}=await reader.read();if(done)break;size+=value.byteLength;
    if(size>limit){await reader.cancel();throw new Error('TOO_LARGE');}chunks.push(value);}}
  finally{reader.releaseLock();}
  const bytes=new Uint8Array(size);let at=0;for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.length;}
  const result=JSON.parse(new TextDecoder().decode(bytes));
  if(!result||typeof result!=='object'||Array.isArray(result))throw new Error('INVALID_JSON');
  return result;
}
export function approvedSignal(message,role,approved) {
  if(!message||typeof message!=='object')return false;
  if(message.type==='offer')return role==='guest'&&approved&&ID_RE.test(message.id||'')&&
    typeof message.sdp==='string'&&message.sdp.startsWith('v=0')&&message.sdp.length<=65_536;
  if(message.type==='answer')return role==='host'&&approved&&ID_RE.test(message.id||'')&&
    (message.ok===false||typeof message.sdp==='string'&&message.sdp.startsWith('v=0')&&message.sdp.length<=65_536);
  return false;
}
export function normalizeIce(value) {
  const entries=Array.isArray(value)?value:[value];
  return entries.filter(v=>v&&typeof v==='object').map(v=>{
    const urls=(Array.isArray(v.urls)?v.urls:[v.urls]).filter(u=>typeof u==='string'&&
      /^(stun|turn|turns):/.test(u)&&!/:53(?:\?|$)/.test(u));
    return {urls,...(typeof v.username==='string'?{username:v.username}:{}),
      ...(typeof v.credential==='string'?{credential:v.credential}:{})};
  }).filter(v=>v.urls.length);
}
