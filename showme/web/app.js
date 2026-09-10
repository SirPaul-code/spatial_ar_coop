import {fitRect, pointInRect, shapePoints, decodeEnvelope} from './geometry.mjs';

const $ = id => document.getElementById(id);
const ui = Object.fromEntries(['joinDialog','join','joinError','name','connection','statusDot','hostTitle','modeBadge',
  'stage','imageWrap','scene','ink','empty','frameStatus','frameInfo','gestureHint','cameraPaused','freeze','label',
  'undo','clear','count','annotationList','voice','voiceHint','voiceState','remoteAudio','snapshot','leave','toast'].map(id=>[id,$(id)]));
const token = location.hash.slice(1);
function uuid() {
  const bytes=new Uint8Array(16); crypto.getRandomValues(bytes);
  return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
}
const viewerId=uuid();
let joined=false, stopped=false, joining=false, serverState={}, frame=null, currentBitmap=null;
let tool='pin', color='#8ff1c6', annotations=[], drawing=null, submitting=false;
let frozen=false, manualFreeze=false, freezeAt=0, freezePromise=null;
let stateTimer=0, frameTimer=0, toastTimer=0, lastFrameAt=0, frameErrorCount=0, loopGeneration=0;
let pc=null, microphone=null, audioConnecting=false, audioMuted=false;
const scene=ui.scene.getContext('2d',{alpha:false});
const ink=ui.ink.getContext('2d');

function toast(text) {
  ui.toast.textContent=text; ui.toast.hidden=false;
  clearTimeout(toastTimer); toastTimer=setTimeout(()=>{ui.toast.hidden=true;},6500);
}
function connection(text,live=false) {
  ui.connection.textContent=text; ui.statusDot.classList.toggle('live',live);
}
async function api(path,body,options={}) {
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),options.timeout??7000);
  try {
    const headers={'Authorization':`Bearer ${token}`,'X-ShowMe-Viewer':viewerId};
    if(body!==undefined)headers['Content-Type']='application/json';
    const response=await fetch(path,{method:body===undefined?'GET':'POST',headers,
      body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:controller.signal});
    if(response.status===204)return null;
    if(options.binary && response.ok)return response.arrayBuffer();
    const result=await response.json();
    if(!response.ok || result.ok===false) {
      const error=new Error(result.message||`Request failed (${response.status})`);
      error.code=result.code||`HTTP_${response.status}`; throw error;
    }
    return result;
  } finally {clearTimeout(timeout);}
}
function reportError(error) {
  if(error.code==='SESSION_ENDED') {endLocal('This session has ended. Ask for a new invitation.');return;}
  toast(error.name==='AbortError'?'The camera phone is not responding. Keep ShowMe open on that phone.':error.message);
}
function resize() {
  if(!frame)return;
  const swap=frame.rotation===90||frame.rotation===270;
  const w=swap?frame.height:frame.width,h=swap?frame.width:frame.height;
  const fit=fitRect(ui.stage.clientWidth,ui.stage.clientHeight,w,h);
  Object.assign(ui.imageWrap.style,{left:`${fit.x}px`,top:`${fit.y}px`,width:`${fit.width}px`,height:`${fit.height}px`});
}
new ResizeObserver(resize).observe(ui.stage);
async function decodeImage(bytes) {
  const blob=new Blob([bytes],{type:'image/jpeg'});
  if(typeof createImageBitmap==='function')return createImageBitmap(blob);
  return new Promise((resolve,reject)=>{
    const image=new Image(),url=URL.createObjectURL(blob);
    image.onload=()=>{URL.revokeObjectURL(url);resolve(image);};
    image.onerror=()=>{URL.revokeObjectURL(url);reject(new Error('Could not decode camera image'));};
    image.src=url;
  });
}
function paintFrame(meta,bitmap) {
  currentBitmap?.close?.();currentBitmap=bitmap;frame=meta;
  const swap=meta.rotation===90||meta.rotation===270;
  const w=swap?meta.height:meta.width,h=swap?meta.width:meta.height;
  ui.scene.width=w;ui.scene.height=h;ui.ink.width=w;ui.ink.height=h;
  scene.save();
  if(meta.rotation===90){scene.translate(w,0);scene.rotate(Math.PI/2);}
  else if(meta.rotation===180){scene.translate(w,h);scene.rotate(Math.PI);}
  else if(meta.rotation===270){scene.translate(0,h);scene.rotate(-Math.PI/2);}
  scene.drawImage(bitmap,0,0,meta.width,meta.height);scene.restore();
  annotations=Array.isArray(meta.annotations)?meta.annotations:[];
  ui.imageWrap.hidden=false;ui.empty.hidden=true;resize();renderInk();updateList();
  ui.frameInfo.textContent=`AR surface data / ${meta.depthPoints??0} depth samples`;
  lastFrameAt=performance.now();frameErrorCount=0;
}
function strokePath(ctx,points,w,h) {
  ctx.beginPath();points.forEach((p,i)=>i?ctx.lineTo(p[0]*w,p[1]*h):ctx.moveTo(p[0]*w,p[1]*h));
}
function paintShape(ctx,a,draft=false) {
  if(!Array.isArray(a.points)||!a.points.length)return;
  const w=ui.ink.width,h=ui.ink.height,scale=Math.max(1,w/480);
  const points=a.points;
  if(points.some(p=>!Array.isArray(p)||p.length!==2||p.some(v=>!Number.isFinite(v))))return;
  const allowed=['#8ff1c6','#ffce73','#ff7897','#8dbaff'];
  const c=allowed.includes(a.color)?a.color:'#8ff1c6';
  const x=points[0][0]*w,y=points[0][1]*h;
  ctx.save();ctx.lineCap='round';ctx.lineJoin='round';ctx.globalAlpha=draft?.8:1;
  if(draft)ctx.setLineDash([5*scale,5*scale]);
  if(a.tool==='pin') {
    ctx.fillStyle=c+'22';ctx.beginPath();ctx.arc(x,y,22*scale,0,Math.PI*2);ctx.fill();
    ctx.strokeStyle='#08101deb';ctx.lineWidth=6*scale;ctx.beginPath();ctx.arc(x,y,12*scale,0,Math.PI*2);ctx.stroke();
    ctx.strokeStyle=c;ctx.lineWidth=2.5*scale;ctx.stroke();
    ctx.fillStyle=c;ctx.beginPath();ctx.arc(x,y,3*scale,0,Math.PI*2);ctx.fill();
  } else {
    strokePath(ctx,points,w,h);if(a.tool==='circle')ctx.closePath();
    ctx.strokeStyle='#08101ddd';ctx.lineWidth=7*scale;ctx.stroke();
    ctx.strokeStyle=c;ctx.lineWidth=3.5*scale;ctx.stroke();
    if(a.tool==='arrow'&&points.length>1){
      const b=points.at(-1),previous=points.at(-2),ex=b[0]*w,ey=b[1]*h;
      const angle=Math.atan2((b[1]-previous[1])*h,(b[0]-previous[0])*w);
      ctx.beginPath();ctx.moveTo(ex-Math.cos(angle-.5)*18*scale,ey-Math.sin(angle-.5)*18*scale);
      ctx.lineTo(ex,ey);ctx.lineTo(ex-Math.cos(angle+.5)*18*scale,ey-Math.sin(angle+.5)*18*scale);ctx.stroke();
    }
  }
  const label=String(a.label||'').slice(0,64);
  if(label){
    ctx.setLineDash([]);ctx.font=`500 ${12*scale}px system-ui`;
    const tw=Math.min(w-20*scale,ctx.measureText(label).width+20*scale);
    const lx=Math.max(8*scale,Math.min(w-tw-8*scale,x+24*scale));
    const ly=Math.max(8*scale,Math.min(h-36*scale,y-17*scale));
    ctx.fillStyle='#101d2deb';ctx.beginPath();
    if(ctx.roundRect)ctx.roundRect(lx,ly,tw,32*scale,8*scale);else ctx.rect(lx,ly,tw,32*scale);
    ctx.fill();ctx.fillStyle='#fff';ctx.fillText(label,lx+10*scale,ly+21*scale,tw-20*scale);
  }
  ctx.restore();
}
function renderInk() {
  ink.clearRect(0,0,ui.ink.width,ui.ink.height);
  annotations.forEach(a=>paintShape(ink,a));
  if(drawing)paintShape(ink,{tool:drawing.tool,color:drawing.color,label:ui.label.value,
    points:shapePoints(drawing.tool,drawing.start,drawing.end,drawing.samples)},true);
}
function updateList(){
  ui.count.textContent=String(serverState.annotations??annotations.length);
  ui.annotationList.replaceChildren();
  if(!annotations.length){const p=document.createElement('p');p.className='fineprint';p.textContent='Visible guidance appears here. Annotations out of view remain in the AR scene.';ui.annotationList.append(p);return;}
  for(const a of annotations){
    const item=document.createElement('div');item.className='annotation-item';
    const dot=document.createElement('span');dot.style.background=['#8ff1c6','#ffce73','#ff7897','#8dbaff'].includes(a.color)?a.color:'#8ff1c6';
    const title=document.createElement('p');title.textContent=a.label||({pin:'Surface pin',arrow:'Direction arrow',draw:'Drawing',circle:'Highlighted area'}[a.tool]??'Annotation');
    const remove=document.createElement('button');remove.textContent='×';remove.setAttribute('aria-label',`Remove ${title.textContent}`);
    remove.addEventListener('click',()=>mutate('remove',a.id));item.append(dot,title,remove);ui.annotationList.append(item);
  }
}
async function fetchFrame(generation){
  if(!joined||stopped||generation!==loopGeneration)return;
  try {
    if(!frozen&&!drawing&&!submitting&&!serverState.paused){
      const buffer=await api(`/api/frame?after=${frame?.id??-1}`,undefined,{binary:true});
      if(buffer){
        const {meta,jpeg}=decodeEnvelope(buffer);
        const bitmap=await decodeImage(jpeg);
        // A response already in flight must never replace the image being annotated.
        if(!frozen&&!drawing&&!submitting&&joined&&generation===loopGeneration)paintFrame(meta,bitmap);
        else bitmap.close?.();
      }
    }
  } catch(error){
    frameErrorCount++;
    if(error.code==='SESSION_ENDED'){reportError(error);return;}
    if(frameErrorCount===3)connection('Reconnecting to camera…');
  } finally {
    if(joined&&generation===loopGeneration)frameTimer=setTimeout(()=>fetchFrame(generation),Math.min(120+frameErrorCount*200,2000));
  }
}
async function pollState(generation){
  if(!joined||stopped||generation!==loopGeneration)return;
  try{
    serverState=await api('/api/state');
    if(serverState.active===false){endLocal('The owner ended this session.');return;}
    ui.hostTitle.textContent=`${serverState.hostName||'Your helper'}’s view`;
    ui.modeBadge.textContent=serverState.secure?'LOCAL HTTPS':'LOCAL WI-FI';
    connection(serverState.paused?'Camera paused':serverState.tracking?'Connected / live':'Connected / scanning',!serverState.paused&&serverState.tracking);
    ui.cameraPaused.hidden=!serverState.paused;
    ui.count.textContent=String(serverState.annotations??0);
    if(frame&&serverState.epoch!==frame.epoch){
      drawing=null;annotations=[];frame=null;frozen=false;manualFreeze=false;freezePromise=null;
      renderInk();updateFreezeUi();ui.imageWrap.hidden=true;ui.empty.hidden=false;
      toast('The camera session changed. Waiting for a fresh view.');
    }
    if(frozen&&performance.now()-freezeAt>55000&&!submitting){await resumeLive();toast('Frozen frame expired. Live view resumed to keep placement reliable.');}
    if(!pc&&!audioConnecting){
      ui.voiceHint.textContent=!isSecureContext?'This local HTTP link supports video and drawing. Browser microphone access needs trusted HTTPS. You can try listen-only audio, or use a separate call.':
        !serverState.voiceEnabled?'Ask the camera owner to tap Enable voice.':'Connect your microphone for a direct, two-way conversation.';
      ui.voice.textContent=!isSecureContext?'Listen to owner':'Connect voice';
    }
  }catch(error){if(error.code==='SESSION_ENDED'){reportError(error);return;}connection('Connection interrupted');}
  finally{if(joined&&generation===loopGeneration)stateTimer=setTimeout(()=>pollState(generation),1000);}
}
async function join(){
  if(joining||joined)return;
  if(token.length<16){ui.joinError.textContent='This page needs the full invitation link, including the part after #.';return;}
  joining=true;ui.join.disabled=true;ui.joinError.textContent='';
  try{
    serverState=await api('/api/join',{viewerId,name:ui.name.value.trim()||'Helper'});
    joined=true;stopped=false;loopGeneration++;
    ui.joinDialog.close();connection('Connected',true);
    const generation=loopGeneration;fetchFrame(generation);pollState(generation);
  }catch(error){ui.joinError.textContent=error.name==='AbortError'?'Cannot reach the phone. Check that both devices are on the same Wi-Fi or hotspot.':error.message;}
  finally{joining=false;ui.join.disabled=false;}
}
function updateFreezeUi(){
  ui.frameStatus.hidden=!frozen;
  ui.freeze.textContent=frozen?'▶ Resume live view':'Ⅱ Freeze frame';
  ui.gestureHint.textContent=submitting?'Attaching your guidance to the physical surface…':frozen?'Frozen image. Draw on a clear, mapped surface.':'Choose a tool, then point into their view.';
}
function holdFrame(manual=false){
  if(!frame||!joined)return Promise.reject(new Error('Wait for a camera image first.'));
  if(manual)manualFreeze=true;
  if(frozen)return freezePromise||Promise.resolve();
  frozen=true;freezeAt=performance.now();updateFreezeUi();
  freezePromise=api('/api/freeze',{frameId:frame.id,epoch:frame.epoch});
  // Attach a rejection handler immediately; pointer-up still receives the original rejection.
  freezePromise.catch(()=>{});
  return freezePromise;
}
async function resumeLive(){
  drawing=null;frozen=false;manualFreeze=false;freezePromise=null;updateFreezeUi();renderInk();
  if(joined)await api('/api/resume',{}).catch(()=>{});
}
ui.freeze.addEventListener('click',async()=>{
  if(submitting)return;
  try{if(frozen)await resumeLive();else await holdFrame(true);}catch(error){await resumeLive();reportError(error);}
});
ui.ink.addEventListener('pointerdown',event=>{
  if(event.button!==0||!joined||!frame||submitting||serverState.paused||!serverState.tracking)return;
  if(drawing)return;
  const point=pointInRect(event.clientX,event.clientY,ui.ink.getBoundingClientRect());if(!point)return;
  event.preventDefault();ui.ink.setPointerCapture(event.pointerId);
  const saved=frame;const pinning=holdFrame(false);
  drawing={pointerId:event.pointerId,start:point,end:point,samples:[],tool,color,frameId:saved.id,epoch:saved.epoch,pinning};
  renderInk();
});
ui.ink.addEventListener('pointermove',event=>{
  if(!drawing||event.pointerId!==drawing.pointerId)return;
  const point=pointInRect(event.clientX,event.clientY,ui.ink.getBoundingClientRect());if(!point)return;
  event.preventDefault();drawing.end=point;
  if(drawing.tool==='draw'){
    const previous=drawing.samples.at(-1)||drawing.start;
    if(Math.hypot(point[0]-previous[0],point[1]-previous[1])>.004){drawing.samples.push(point);if(drawing.samples.length>500)drawing.samples=drawing.samples.filter((_,i)=>i%2===0);}
  }
  renderInk();
});
ui.ink.addEventListener('pointerup',async event=>{
  if(!drawing||drawing.pointerId!==event.pointerId)return;
  event.preventDefault();
  const active=drawing;
  const finalPoint=pointInRect(event.clientX,event.clientY,ui.ink.getBoundingClientRect());if(finalPoint)active.end=finalPoint;
  if(active.tool!=='pin'&&Math.hypot(active.end[0]-active.start[0],active.end[1]-active.start[1])<.005&&active.samples.length<3){
    drawing=null;if(!manualFreeze)await resumeLive();renderInk();return;
  }
  submitting=true;updateFreezeUi();
  try{
    await active.pinning;
    const result=await api('/api/draw',{action:'draw',requestId:uuid(),frameId:active.frameId,epoch:active.epoch,
      tool:active.tool,color:active.color,label:ui.label.value.trim(),points:shapePoints(active.tool,active.start,active.end,active.samples)});
    if(Array.isArray(result.annotations))annotations=result.annotations;
    serverState.annotations=Math.max(serverState.annotations??0,annotations.length);
    toast('Attached to their world. The drawing stays on the surface.');
    updateList();
  }catch(error){reportError(error);}
  finally{
    drawing=null;submitting=false;renderInk();updateFreezeUi();
    if(!manualFreeze)await resumeLive();
  }
});
ui.ink.addEventListener('pointercancel',()=>{drawing=null;renderInk();if(!manualFreeze)resumeLive();});
for(const button of document.querySelectorAll('[data-tool]'))button.addEventListener('click',()=>{
  tool=button.dataset.tool;
  document.querySelectorAll('[data-tool]').forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-pressed',String(b===button));});
});
for(const button of document.querySelectorAll('[data-color]'))button.addEventListener('click',()=>{
  color=button.dataset.color;
  document.querySelectorAll('[data-color]').forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-pressed',String(b===button));});
});
async function mutate(action,id){
  if(!joined||submitting)return;
  if(action==='clear'&&!confirm('Remove all annotations from their world?'))return;
  submitting=true;
  try{await api('/api/draw',{action,id,requestId:uuid()});await resumeLive();frame=null;annotations=[];renderInk();toast(action==='undo'?'Last drawing removed.':'Guidance updated.');}
  catch(error){reportError(error);}finally{submitting=false;updateFreezeUi();}
}
ui.undo.addEventListener('click',()=>mutate('undo'));
ui.clear.addEventListener('click',()=>mutate('clear'));
document.addEventListener('keydown',event=>{
  if(['INPUT','TEXTAREA'].includes(document.activeElement?.tagName)||ui.joinDialog.open)return;
  if(['1','2','3','4'].includes(event.key)){document.querySelectorAll('[data-tool]')[Number(event.key)-1].click();event.preventDefault();}
  if(event.code==='Space'){ui.freeze.click();event.preventDefault();}
  if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='z'){mutate('undo');event.preventDefault();}
});
function waitForIce(peer){
  if(peer.iceGatheringState==='complete')return Promise.resolve();
  return new Promise(resolve=>{
    const done=()=>{clearTimeout(timer);peer.removeEventListener('icegatheringstatechange',changed);resolve();};
    const changed=()=>{if(peer.iceGatheringState==='complete')done();};
    const timer=setTimeout(done,5000);peer.addEventListener('icegatheringstatechange',changed);
  });
}
async function stopVoice(notify=true){
  const previous=pc;pc=null;previous?.close();microphone?.getTracks().forEach(t=>t.stop());microphone=null;
  ui.remoteAudio.srcObject=null;audioMuted=false;audioConnecting=false;
  ui.voiceState.textContent='Off';ui.voice.textContent='Connect voice';
  if(notify&&joined)await api('/api/voice-stop',{}).catch(()=>{});
}
async function connectVoice(){
  if(!joined||audioConnecting)return;
  if(pc){
    if(microphone){audioMuted=!audioMuted;microphone.getAudioTracks().forEach(t=>t.enabled=!audioMuted);ui.voice.textContent=audioMuted?'Unmute microphone':'Mute microphone';ui.voiceState.textContent=audioMuted?'Muted':'Connected';}
    else await stopVoice();return;
  }
  if(!serverState.voiceEnabled){toast('Ask the camera owner to tap Enable voice first.');return;}
  if(typeof RTCPeerConnection==='undefined'){toast('This browser does not support the local audio connection. Video and drawing are still available.');return;}
  audioConnecting=true;ui.voice.disabled=true;ui.voiceState.textContent='Connecting';
  try{
    const peer=new RTCPeerConnection({iceServers:[]});pc=peer;
    if(isSecureContext&&navigator.mediaDevices?.getUserMedia){
      try{microphone=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});}
      catch(error){toast('Microphone not available. Connecting listen-only; you can use a separate call.');}
    }
    if(microphone)microphone.getAudioTracks().forEach(track=>peer.addTrack(track,microphone));
    else peer.addTransceiver('audio',{direction:'recvonly'});
    peer.ontrack=event=>{
      ui.remoteAudio.srcObject=event.streams[0]||new MediaStream([event.track]);
      ui.remoteAudio.play().catch(()=>toast('Tap Connect voice again to allow audio playback.'));
    };
    peer.onconnectionstatechange=()=>{
      if(pc!==peer)return;
      if(peer.connectionState==='connected')ui.voiceState.textContent=microphone?'Connected':'Listen-only';
      else if(['failed','closed','disconnected'].includes(peer.connectionState))ui.voiceState.textContent='Interrupted';
    };
    await peer.setLocalDescription(await peer.createOffer());await waitForIce(peer);
    const result=await api('/api/voice',{sdp:peer.localDescription.sdp},{timeout:15000});
    if(pc!==peer)return;
    await peer.setRemoteDescription({type:'answer',sdp:result.sdp});
    ui.voice.textContent=microphone?'Mute microphone':'Stop listening';
    ui.voiceHint.textContent=microphone?'Two-way local audio. Use headphones if both devices are in the same room.':'Listen-only audio. A trusted HTTPS origin is required for the browser microphone.';
  }catch(error){await stopVoice();toast(`Voice: ${error.message}. Video and drawing remain available.`);}
  finally{audioConnecting=false;ui.voice.disabled=false;}
}
ui.voice.addEventListener('click',connectVoice);
ui.snapshot.addEventListener('click',()=>{
  if(!frame){toast('Wait for a camera image first.');return;}
  const canvas=document.createElement('canvas');canvas.width=ui.scene.width;canvas.height=ui.scene.height;
  const context=canvas.getContext('2d');context.drawImage(ui.scene,0,0);context.drawImage(ui.ink,0,0);
  canvas.toBlob(blob=>{
    if(!blob)return;
    const a=document.createElement('a'),url=URL.createObjectURL(blob);a.href=url;a.download=`ShowMe-${Date.now()}.png`;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);
  },'image/png');
});
function endLocal(message){
  joined=false;stopped=true;loopGeneration++;clearTimeout(frameTimer);clearTimeout(stateTimer);
  stopVoice(false);drawing=null;frozen=false;manualFreeze=false;submitting=false;connection('Session ended');
  ui.cameraPaused.hidden=false;ui.cameraPaused.querySelector('strong').textContent='Session ended';ui.cameraPaused.querySelector('span').textContent=message;
  ui.joinError.textContent=message;ui.joinDialog.showModal();
}
ui.leave.addEventListener('click',async()=>{
  if(!joined)return;
  if(!confirm('Leave this ShowMe session? Your annotations remain on the owner’s phone.'))return;
  await api('/api/leave',{}).catch(()=>{});endLocal('You left the session. Rejoin using the same invitation while it is active.');
});
ui.join.addEventListener('click',join);
ui.name.addEventListener('keydown',event=>{if(event.key==='Enter')join();});
ui.joinDialog.addEventListener('cancel',event=>event.preventDefault());
window.addEventListener('pagehide',()=>{pc?.close();microphone?.getTracks().forEach(t=>t.stop());});
ui.joinDialog.showModal();
if(!token)ui.joinError.textContent='Open the invitation link shared by the camera owner. This page alone cannot join a session.';
