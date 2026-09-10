import test from 'node:test';
import assert from 'node:assert/strict';
import {stampBytes,decodeStampBytes,decodeStampRows,crc16} from './live-video.mjs';

test('stamp CRC matches the standard CCITT-FALSE check vector',()=>{
  assert.equal(crc16(new TextEncoder().encode('123456789')),0x29b1);
});
test('all 32 frame ID bits survive serialization; epochs intentionally wrap to 8 bits',()=>{
  for(const id of [1,2,31,255,256,65535,65536,0x7fffffff,0x80000000,0xffffffff]){
    assert.deepEqual(decodeStampBytes(stampBytes(id,259)),{id,epoch:3});
  }
  assert.throws(()=>stampBytes(0,1));assert.throws(()=>stampBytes(0x100000000,1));
});
test('every single bit corruption is detected',()=>{
  const valid=stampBytes(891234,12);
  for(let bit=0;bit<64;bit++){
    const bytes=valid.slice();bytes[bit>>3]^=1<<(bit%8);assert.equal(decodeStampBytes(bytes),null);
  }
});
function row(id,epoch,width,inverted=false,noise=0){
  const bytes=stampBytes(id,epoch),rgba=new Uint8ClampedArray(width*4);
  for(let x=0;x<width;x++){
    const cell=Math.min(63,Math.floor(x*64/width));
    const bit=(((bytes[cell>>3]>>(7-cell%8))&1)===1)!==inverted;
    const y=(bit?235:18)+(noise?((x*17)%noise-noise/2):0);
    rgba[x*4]=y;rgba[x*4+1]=y;rgba[x*4+2]=y;rgba[x*4+3]=255;
  }
  return rgba;
}
test('image stamp survives luma offsets/noise and resolution adaptation',()=>{
  for(const w of [320,360,480,640,720,1280]){
    assert.deepEqual(decodeStampRows(row(123456,3,w,false,35),row(123456,3,w,true,35),w),{id:123456,epoch:3});
  }
});
test('contradictory valid rows and too small images fail closed',()=>{
  assert.equal(decodeStampRows(row(1,3,480),row(2,3,480,true),480),null);
  assert.equal(decodeStampRows(row(1,3,160),row(1,3,160,true),160),null);
});
test('one intact row can recover a frame but a completely missing tag cannot',()=>{
  const blank=new Uint8ClampedArray(480*4);
  assert.deepEqual(decodeStampRows(row(431,10,480),blank,480),{id:431,epoch:10});
  assert.equal(decodeStampRows(blank,blank,480),null);
});
