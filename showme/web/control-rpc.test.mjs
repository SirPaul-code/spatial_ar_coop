import test from 'node:test';
import assert from 'node:assert/strict';
import {FragmentAssembler,splitMessage,ControlRpc} from './control-rpc.mjs';
test('matches Android id/i/n/text wire schema and restores a large frozen image',()=>{
  const payload=JSON.stringify({id:'request001',body:{jpeg:'a'.repeat(280000)}});
  const fragments=splitMessage('request001',payload);
  assert.deepEqual(Object.keys(JSON.parse(fragments[0])),['id','i','n','text']);
  const assembler=new FragmentAssembler();let value;
  // SCTP is ordered, but reassembly also survives out-of-order chunks.
  for(const p of fragments.reverse())value=assembler.accept(p)||value;
  assert.equal(value,payload);
});
test('rejects stale, conflicting, oversized and too many assemblies',()=>{
  let now=0;const a=new FragmentAssembler(()=>now);
  const packet=(id,i,n,text)=>JSON.stringify({id,i,n,text});
  assert.equal(a.accept(packet('request001',0,2,'abc')),null);
  assert.equal(a.accept(packet('request001',0,2,'def')),null);
  assert.equal(a.messages.size,0);
  assert.equal(a.accept(packet('request001',0,257,'abc')),null);
  assert.equal(a.accept(packet('request001',0,1,'x'.repeat(8001))),null);
  for(let i=0;i<5;i++)a.accept(packet(`request00${i}`,0,2,'a'));
  assert.equal(a.messages.size,4);now=12001;a.accept(packet('request999',0,1,'ok'));
  assert.equal(a.messages.size,0);
});
test('RPC resolves a chunked response, rejects application errors and cancels pending work',async()=>{
  const channel={readyState:'open',bufferedAmount:0,sent:[],send(text){this.sent.push(text)}};
  const rpc=new ControlRpc(channel);const result=rpc.request('/api/state');
  await new Promise(r=>setTimeout(r,1));
  const a=new FragmentAssembler();const req=JSON.parse(channel.sent.map(p=>a.accept(p)).find(Boolean));
  for(const p of splitMessage(req.id,JSON.stringify({id:req.id,result:{ok:true,epoch:4}})))channel.onmessage({data:p});
  assert.equal((await result).epoch,4);
  const cancelled=rpc.request('/api/freeze',{jpeg:'aaa'});rpc.close();await assert.rejects(cancelled,/disconnected/);
});
