const MAX_BYTES=2_048_000,CHUNK=8000,MAX_PARTS=256;
export function requestId(){const b=new Uint8Array(16);crypto.getRandomValues(b);return [...b].map(v=>v.toString(16).padStart(2,'0')).join('');}
export function splitMessage(id,text){
  if(!/^[A-Za-z0-9-]{8,80}$/.test(id)||text.length>MAX_BYTES)throw new Error('Invalid control message');
  const total=Math.max(1,Math.ceil(text.length/CHUNK));
  return Array.from({length:total},(_,index)=>JSON.stringify({id,i:index,n:total,text:text.slice(index*CHUNK,(index+1)*CHUNK)}));
}
export class FragmentAssembler {
  constructor(clock=()=>performance.now()){this.clock=clock;this.messages=new Map();}
  accept(text){
    const now=this.clock();for(const [id,m] of this.messages)if(now-m.at>12000)this.messages.delete(id);
    if(typeof text!=='string'||text.length>48000)return null;
    let p;try{p=JSON.parse(text);}catch{return null;}
    if(!/^[A-Za-z0-9-]{8,80}$/.test(p.id||'')||!Number.isInteger(p.n)||p.n<1||p.n>MAX_PARTS||
       !Number.isInteger(p.i)||p.i<0||p.i>=p.n||typeof p.text!=='string'||p.text.length>CHUNK)return null;
    let m=this.messages.get(p.id);
    if(!m){if(this.messages.size>=4)return null;m={at:now,total:p.n,parts:new Map(),length:0};this.messages.set(p.id,m);}
    if(m.total!==p.n){this.messages.delete(p.id);return null;}
    if(m.parts.has(p.i)){if(m.parts.get(p.i)!==p.text)this.messages.delete(p.id);return null;}
    m.parts.set(p.i,p.text);m.length+=p.text.length;
    if(m.length>MAX_BYTES){this.messages.delete(p.id);return null;}
    if(m.parts.size!==m.total)return null;
    this.messages.delete(p.id);return Array.from({length:m.total},(_,i)=>m.parts.get(i)).join('');
  }
  clear(){this.messages.clear();}
}
export class ControlRpc {
  constructor(channel){
    this.channel=channel;this.pending=new Map();this.fragments=new FragmentAssembler();
    channel.onmessage=event=>{
      const text=this.fragments.accept(event.data);if(!text)return;
      let response;try{response=JSON.parse(text);}catch{return;}
      const job=this.pending.get(response.id);if(!job)return;
      this.pending.delete(response.id);clearTimeout(job.timer);
      if(response.result?.ok===false){const e=new Error(response.result.message||'Request failed');e.code=response.result.code;job.reject(e);}
      else job.resolve(response.result);
    };
    channel.onclose=()=>this.close();
  }
  get ready(){return this.channel.readyState==='open';}
  request(path,body={}){
    if(!this.ready)return Promise.reject(new Error('The call is connecting. Please try again in a moment.'));
    if(this.pending.size>=8)return Promise.reject(new Error('Please wait for the current drawing to finish.'));
    const id=requestId();
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('The camera did not answer in time.'));},15000);
      this.pending.set(id,{resolve,reject,timer});
      const parts=splitMessage(id,JSON.stringify({id,path,body}));
      (async()=>{
        const deadline=performance.now()+12000;
        for(const part of parts){
          while(this.ready&&this.channel.bufferedAmount>128000&&performance.now()<deadline)await new Promise(r=>setTimeout(r,4));
          if(!this.ready||performance.now()>=deadline)throw new Error('Call interrupted while sending this drawing.');
          this.channel.send(part);
        }
      })().catch(error=>{const job=this.pending.get(id);if(job){this.pending.delete(id);clearTimeout(timer);reject(error);}});
    });
  }
  close(){for(const job of this.pending.values()){clearTimeout(job.timer);job.reject(new Error('Call disconnected'));}this.pending.clear();this.fragments.clear();}
}
