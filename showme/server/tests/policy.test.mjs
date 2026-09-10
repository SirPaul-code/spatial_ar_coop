import test from 'node:test';
import assert from 'node:assert/strict';
import {approvedSignal,bearer,boundedInt,cleanName,normalizeIce,readJson,sameSecret,token} from '../src/policy.mjs';

test('tokens and owner secret are not public room IDs',async()=>{
  assert.match(token(18),/^[A-Za-z0-9_-]{24}$/);assert.match(token(),/^[A-Za-z0-9_-]{32}$/);
  const a=token(),b=token();assert.notEqual(a,b);assert.equal(await sameSecret(a,a),true);
  assert.equal(await sameSecret(a,b),false);assert.equal(await sameSecret('',undefined),false);
});
test('only approved helper offers and owner answers can cross signaling',()=>{
  const offer={type:'offer',id:'request001',sdp:'v=0\r\n'};
  assert.equal(approvedSignal(offer,'guest',true),true);
  assert.equal(approvedSignal(offer,'host',true),false);
  assert.equal(approvedSignal(offer,'guest',false),false);
  assert.equal(approvedSignal({...offer,sdp:'x'.repeat(100000)},'guest',true),false);
  assert.equal(approvedSignal({...offer,type:'draw'},'guest',true),false);
  assert.equal(approvedSignal({type:'answer',id:'request001',ok:false},'host',true),true);
});
test('ICE keeps TURN TCP TLS fallback but not browser-blocked port53 or non-ICE URLs',()=>{
  const entries=normalizeIce([{urls:['turn:x:53?transport=udp','turn:x:3478?transport=udp','turns:x:443?transport=tcp','https://evil'],username:'u',credential:'c'}]);
  assert.deepEqual(entries[0].urls,['turn:x:3478?transport=udp','turns:x:443?transport=tcp']);
  assert.equal(entries[0].credential,'c');
});
test('websocket capability lives in a header, not a query parameter',()=>{
  assert.equal(bearer(new Request('https://test/?token=ignored',{headers:{'Sec-WebSocket-Protocol':'showme.v1, cap.example'}})),'example');
  assert.equal(bearer(new Request('https://test/?token=ignored')),'');
});
test('payload reader and session duration cannot be unbounded',async()=>{
  assert.equal(boundedInt('999999',1800,300,3600),1800);
  assert.equal(cleanName('\u0000Alice\n'),'Alice');
  const request=new Request('https://test',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({x:'a'.repeat(5000)})});
  await assert.rejects(readJson(request));
});
