import test from 'node:test';
import assert from 'node:assert/strict';
import {fitRect, pointInRect, shapePoints, decodeEnvelope} from './geometry.mjs';

test('portrait image is letterboxed, not stretched or cropped',()=>{
  assert.deepEqual(fitRect(1000,600,480,640),{x:275,y:0,width:450,height:600});
  assert.deepEqual(fitRect(0,600,480,640),{x:0,y:0,width:0,height:0});
});
test('pointer coordinates exclude the letterbox and preserve normalized image pixels',()=>{
  const rect={left:275,top:0,width:450,height:600};
  assert.deepEqual(pointInRect(500,300,rect),[.5,.5]);
  assert.equal(pointInRect(200,300,rect),null);
  assert.equal(pointInRect(500,-10,rect),null);
});
test('arrows preserve both endpoints and sample the whole physical surface',()=>{
  const p=shapePoints('arrow',[.2,.3],[.8,.7]);
  assert.equal(p.length,12);
  assert.deepEqual(p[0],[.2,.3]);
  assert.ok(Math.abs(p.at(-1)[0]-.8)<1e-12);
  assert.ok(Math.abs(p.at(-1)[1]-.7)<1e-12);
});
test('freehand samples are bounded and retain stroke endpoints',()=>{
  const samples=Array.from({length:300},(_,i)=>[i/300,.5]);
  const p=shapePoints('draw',[0,.5],[1,.5],samples);
  assert.equal(p.length,64);
  assert.deepEqual(p[0],[0,.5]);assert.deepEqual(p.at(-1),[1,.5]);
});
test('circle stays within drag bounds in either direction',()=>{
  for(const [start,end] of [[[.2,.3],[.8,.9]],[[.8,.9],[.2,.3]]]){
    const p=shapePoints('circle',start,end);
    assert.equal(p.length,32);
    assert.ok(p.every(([x,y])=>x>=.199999&&x<=.800001&&y>=.299999&&y<=.900001));
  }
});
function packet(meta,jpeg=new Uint8Array([255,216,255,217])){
  const bytes=new TextEncoder().encode(JSON.stringify(meta));
  const result=new Uint8Array(4+bytes.length+jpeg.length);
  new DataView(result.buffer).setUint32(0,bytes.length,false);result.set(bytes,4);result.set(jpeg,4+bytes.length);
  return result.buffer;
}
test('frame metadata and image travel in one atomic envelope',()=>{
  const {meta,jpeg}=decodeEnvelope(packet({id:41,epoch:2,width:640,height:480,rotation:90}));
  assert.equal(meta.id,41);assert.equal(meta.epoch,2);assert.equal(jpeg[0],255);assert.equal(jpeg.length,4);
});
test('rejects malformed, oversized or inconsistent frame metadata',()=>{
  assert.throws(()=>decodeEnvelope(new ArrayBuffer(3)));
  assert.throws(()=>decodeEnvelope(new ArrayBuffer(5*1024*1024)));
  const good={id:41,epoch:2,width:640,height:480,rotation:90};
  for(const bad of [{...good,id:1e20},{...good,rotation:45},{...good,width:-1},{...good,height:99999}]){
    assert.throws(()=>decodeEnvelope(packet(bad)));
  }
  const corrupt=packet(good);new DataView(corrupt).setUint32(0,999999,false);
  assert.throws(()=>decodeEnvelope(corrupt));
});
