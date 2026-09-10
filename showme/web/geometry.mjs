export function fitRect(cw, ch, w, h) {
  if (![cw,ch,w,h].every(n => Number.isFinite(n) && n > 0)) return {x:0,y:0,width:0,height:0};
  const scale = Math.min(cw/w, ch/h);
  return { x:(cw-w*scale)/2, y:(ch-h*scale)/2, width:w*scale, height:h*scale };
}
export function pointInRect(x,y,rect) {
  if (!rect.width || !rect.height) return null;
  const u=(x-rect.left)/rect.width, v=(y-rect.top)/rect.height;
  return u>=0 && u<=1 && v>=0 && v<=1 ? [u,v] : null;
}
export function shapePoints(tool, start, end, samples=[]) {
  if (tool==='pin') return [start];
  if (tool==='circle') {
    const cx=(start[0]+end[0])/2, cy=(start[1]+end[1])/2;
    const rx=Math.abs(end[0]-start[0])/2, ry=Math.abs(end[1]-start[1])/2;
    return Array.from({length:32},(_,i)=>[cx+rx*Math.cos(i*Math.PI/16),cy+ry*Math.sin(i*Math.PI/16)]);
  }
  if (tool==='arrow') return Array.from({length:12},(_,i)=>start.map((v,j)=>v+(end[j]-v)*i/11));
  const points=[start,...samples,end];
  return points.length<=64 ? points : Array.from({length:64},(_,i)=>points[Math.round(i*(points.length-1)/63)]);
}
export function decodeEnvelope(buffer) {
  if (buffer.byteLength<5 || buffer.byteLength>4*1024*1024) throw new Error('Invalid camera packet');
  const size=new DataView(buffer).getUint32(0,false);
  if(size<2 || size>262144 || size+4>=buffer.byteLength) throw new Error('Invalid camera metadata');
  const meta=JSON.parse(new TextDecoder().decode(new Uint8Array(buffer,4,size)));
  if (!Number.isSafeInteger(meta.id) || !Number.isInteger(meta.epoch) || ![0,90,180,270].includes(meta.rotation) ||
      ![meta.width,meta.height].every(n=>Number.isInteger(n)&&n>0&&n<=4096)) throw new Error('Unsupported camera geometry');
  return {meta,jpeg:new Uint8Array(buffer,4+size)};
}
