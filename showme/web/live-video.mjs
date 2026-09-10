import {ControlRpc} from './control-rpc.mjs';
/**
 * Live video is a real RTP media track. A small CRC-coded footer is part of the
 * encoded image and cropped from presentation. The frame ID cannot run ahead of
 * the image when WebRTC drops, buffers, adapts or reorders frames.
 */
export function crc16(bytes,length=bytes.length) {
  let crc=0xffff;
  for(let i=0;i<length;i++){
    crc^=bytes[i]<<8;
    for(let bit=0;bit<8;bit++)crc=((crc<<1)^((crc&0x8000)?0x1021:0))&0xffff;
  }
  return crc;
}
export function stampBytes(id,epoch) {
  if(!Number.isInteger(id)||id<1||id>0xffffffff)throw new Error('Invalid video frame id');
  const bytes=new Uint8Array([0xa7,id>>>24,id>>>16,id>>>8,id,epoch,0,0]);
  const crc=crc16(bytes,6);bytes[6]=crc>>>8;bytes[7]=crc;return bytes;
}
export function decodeStampBytes(bytes) {
  if(bytes.length!==8||bytes[0]!==0xa7||crc16(bytes,6)!==((bytes[6]<<8)|bytes[7]))return null;
  const id=(bytes[1]*16777216+bytes[2]*65536+bytes[3]*256+bytes[4])>>>0;
  return id?{id,epoch:bytes[5]}:null;
}
export function decodeStampRows(top,bottom,width) {
  if(width<256||top.length<width*4||bottom.length<width*4)return null;
  function row(bytes,inverted){
    const samples=Array.from({length:64},(_,cell)=>{
      const a=Math.floor((cell+.32)*width/64),b=Math.max(a+1,Math.floor((cell+.68)*width/64));
      let sum=0;for(let x=a;x<b;x++)sum+=(bytes[x*4]+bytes[x*4+1]+bytes[x*4+2])/3;
      return sum/(b-a);
    });
    const low=Math.min(...samples),high=Math.max(...samples);if(high-low<70)return null;
    const threshold=(low+high)/2,result=new Uint8Array(8);
    samples.forEach((v,i)=>{const bit=(v>threshold)!==inverted;result[i>>>3]|=Number(bit)<<(7-(i%8));});
    return decodeStampBytes(result);
  }
  const a=row(top,false),b=row(bottom,true);
  if(a&&b&&(a.id!==b.id||a.epoch!==b.epoch))return null;
  return a||b;
}

export class LiveVideoCall {
  constructor({api,onFrame,onMetadata=()=>{},onStatus=()=>{},onStats=()=>{},onAudio=()=>{}}){
    Object.assign(this,{api,onFrame,onMetadata,onStatus,onStats,onAudio});
    this.video=document.createElement('video');
    this.video.autoplay=true;this.video.playsInline=true;this.video.muted=true;
    this.video.setAttribute('aria-hidden','true');
    Object.assign(this.video.style,{position:'fixed',width:'1px',height:'1px',opacity:'.01',pointerEvents:'none',left:'0',top:'0'});
    document.body.append(this.video);
    this.buffer=document.createElement('canvas');this.ctx=this.buffer.getContext('2d',{alpha:false});
    this.footer=document.createElement('canvas');this.footer.height=2;this.footerCtx=this.footer.getContext('2d',{willReadFrequently:true});
    this.meta=new Map();this.frozen=false;this.serial=0;this.pc=null;this.microphone=null;
    this.control=null;this.audioSender=null;this.iceServers=[];this.layout=null;this.connecting=false;this.videoCallback=null;this.statsTimer=0;
    this.framesSinceStats=0;this.lastStatsAt=performance.now();this.remoteAudio=null;
  }
  get connected(){return this.pc?.connectionState==='connected';}
  async connect({microphone=false}={}){
    if(this.connecting)return;
    if(typeof RTCPeerConnection==='undefined')throw new Error('This browser cannot receive WebRTC video. Use a current browser.');
    this.disconnect();this.connecting=true;const serial=this.serial;
    try{
      const configuration=await this.api('/api/ice').catch(error=>{if(location.pathname.startsWith('/r/'))throw error;return {iceServers:[]};});
      const pc=new RTCPeerConnection({iceServers:configuration.iceServers??[]});this.pc=pc;
      const video=pc.addTransceiver('video',{direction:'recvonly'});
      const audio=pc.addTransceiver('audio',{direction:'sendrecv'});this.audioSender=audio.sender;
      this.control=new ControlRpc(pc.createDataChannel('showme-control',{ordered:true}));
      // Prefer a standard hardware-friendly codec; retain alternatives for negotiation.
      if(video.setCodecPreferences&&globalThis.RTCRtpReceiver?.getCapabilities){
        const codecs=RTCRtpReceiver.getCapabilities('video')?.codecs??[];
        const ordered=[...codecs.filter(c=>c.mimeType.toLowerCase()==='video/h264'),...codecs.filter(c=>c.mimeType.toLowerCase()!=='video/h264')];
        if(ordered.length)video.setCodecPreferences(ordered);
      }
      if(microphone&&isSecureContext&&navigator.mediaDevices?.getUserMedia){
        try {
          this.microphone=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
          if(serial!==this.serial){this.microphone.getTracks().forEach(t=>t.stop());return;}
          await audio.sender.replaceTrack(this.microphone.getAudioTracks()[0]);
        }catch{this.onStatus('MICROPHONE_DENIED');}
      }
      const channel=pc.createDataChannel('showme-frames',{ordered:false,maxRetransmits:0});
      channel.onmessage=event=>{
        if(serial!==this.serial||typeof event.data!=='string'||event.data.length>65000)return;
        try{
          const m=JSON.parse(event.data);
          if(!Number.isSafeInteger(m.id)||m.id<1||!Number.isInteger(m.epoch))return;
          this.meta.set(m.id,m);while(this.meta.size>180)this.meta.delete(this.meta.keys().next().value);
          this.onMetadata(m);
        }catch{/* A bad data packet cannot pause media. */}
      };
      pc.ontrack=event=>{
        if(serial!==this.serial)return;
        if(event.track.kind==='video'){
          this.video.srcObject=new MediaStream([event.track]);
          this.video.play().then(()=>this.scheduleFrame(serial)).catch(()=>this.onStatus('PLAY_REQUIRED'));
        }else{
          this.remoteAudio=new MediaStream([event.track]);this.onAudio(this.remoteAudio);
        }
      };
      pc.onconnectionstatechange=()=>{
        if(serial===this.serial)this.onStatus(pc.connectionState.toUpperCase());
      };
      this.onStatus('CONNECTING');
      await pc.setLocalDescription(await pc.createOffer());
      await new Promise(resolve=>{
        if(pc.iceGatheringState==='complete'){resolve();return;}
        const done=()=>{clearTimeout(timer);pc.removeEventListener('icegatheringstatechange',change);resolve();};
        const change=()=>{if(pc.iceGatheringState==='complete')done();};
        const timer=setTimeout(done,5000);pc.addEventListener('icegatheringstatechange',change);
      });
      if(serial!==this.serial)return;
      const response=await this.api('/api/call',{sdp:pc.localDescription.sdp},{timeout:15000});
      if(serial!==this.serial)return;
      const layout=response.video;
      if(!layout||!Number.isInteger(layout.videoHeight)||!Number.isInteger(layout.contentHeight)||layout.contentHeight>=layout.videoHeight)
        throw new Error('Camera video layout is not ready. Retry the call.');
      this.layout=layout;
      await pc.setRemoteDescription({type:'answer',sdp:response.sdp});
      this.statsTimer=setInterval(()=>this.reportStats(serial),1000);
    }catch(error){if(serial===this.serial){this.disconnect();this.onStatus('FAILED');}throw error;}
    finally{this.connecting=false;}
  }
  scheduleFrame(serial){
    if(serial!==this.serial||!this.pc)return;
    if(this.videoCallback!==null)return;
    const callback=()=>{this.videoCallback=null;if(serial!==this.serial)return;this.present();this.scheduleFrame(serial);};
    if(this.video.requestVideoFrameCallback)this.videoCallback=this.video.requestVideoFrameCallback(callback);
    else this.videoCallback=requestAnimationFrame(callback);
  }
  present(){
    const v=this.video,l=this.layout;
    if(this.frozen||!l||v.readyState<2||!v.videoWidth||!v.videoHeight)return;
    const w=v.videoWidth,h=v.videoHeight;
    if(this.buffer.width!==w||this.buffer.height!==h){this.buffer.width=w;this.buffer.height=h;}
    // One immutable canvas copy supplies both pixels and identity for this presentation.
    this.ctx.drawImage(v,0,0,w,h);
    const content=Math.round(h*l.contentHeight/l.videoHeight),footer=h-content;
    const y1=Math.min(h-1,Math.floor(content+footer*.25)),y2=Math.min(h-1,Math.floor(content+footer*.75));
    let stamp=null;
    // Keep full video GPU-backed. Read back only two footer rows from that same immutable copy.
    if(this.footer.width!==w)this.footer.width=w;
    this.footerCtx.drawImage(this.buffer,0,y1,w,1,0,0,w,1);
    this.footerCtx.drawImage(this.buffer,0,y2,w,1,0,1,w,1);
    try{const pixels=this.footerCtx.getImageData(0,0,w,2).data;stamp=decodeStampRows(pixels.subarray(0,w*4),pixels.subarray(w*4),w);}catch{}
    const m=stamp?this.meta.get(stamp.id):null;
    const meta=m&&((m.epoch&255)===stamp.epoch)?m:null;
    this.framesSinceStats++;
    this.onFrame({canvas:this.buffer,width:w,contentHeight:content,stamp,meta,layout:l});
  }
  async reportStats(serial){
    if(serial!==this.serial||!this.pc)return;
    try{
      const stats=await this.pc.getStats();if(serial!==this.serial)return;
      let incoming=null,pair=null;
      stats.forEach(report=>{if(report.type==='inbound-rtp'&&(report.kind==='video'||report.mediaType==='video'))incoming=report;});
      stats.forEach(report=>{if(report.type==='transport'&&report.selectedCandidatePairId)pair=stats.get(report.selectedCandidatePairId);});
      const relay=pair&&[stats.get(pair.localCandidateId)?.candidateType,stats.get(pair.remoteCandidateId)?.candidateType].includes('relay');
      const now=performance.now(),displayFps=this.framesSinceStats*1000/(now-this.lastStatsAt);
      this.framesSinceStats=0;this.lastStatsAt=now;
      this.onStats({displayFps,receivedFps:incoming?.framesPerSecond??null,width:incoming?.frameWidth,height:incoming?.frameHeight,
        packetsLost:incoming?.packetsLost??0,framesDropped:incoming?.framesDropped??0,rttMs:(pair?.currentRoundTripTime??0)*1000,relay:!!relay,
        jitterMs:(incoming?.jitter??0)*1000,freezeCount:incoming?.freezeCount??0,
        codec:incoming?.codecId?stats.get(incoming.codecId)?.mimeType:null});
    }catch{}
  }
  async enableMicrophone(){
    if(!this.pc||!this.audioSender)throw new Error('Connect the call first.');
    if(!isSecureContext||!navigator.mediaDevices?.getUserMedia)throw new Error('Microphone requires the HTTPS invitation.');
    if(!this.microphone)this.microphone=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
    await this.audioSender.replaceTrack(this.microphone.getAudioTracks()[0]);this.setMuted(false);
  }
  setMuted(muted){this.microphone?.getAudioTracks().forEach(track=>{track.enabled=!muted;});}
  disconnect(){
    this.serial++;clearInterval(this.statsTimer);this.statsTimer=0;
    if(this.videoCallback!==null){
      if(this.video.cancelVideoFrameCallback)this.video.cancelVideoFrameCallback(this.videoCallback);else cancelAnimationFrame(this.videoCallback);
      this.videoCallback=null;
    }
    this.control?.close();this.control=null;this.audioSender=null;this.pc?.close();this.pc=null;this.microphone?.getTracks().forEach(track=>track.stop());this.microphone=null;
    this.video.srcObject=null;this.meta.clear();this.frozen=false;this.layout=null;
  }
}
