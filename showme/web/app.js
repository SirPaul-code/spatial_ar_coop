import {fitRect, pointInRect, shapePoints, decodeEnvelope} from './geometry.mjs';
import {LiveVideoCall} from './live-video.mjs';

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
let audioConnecting=false, audioMuted=false, reconnectTimer=0, retryCount=0, lastListAt=0;
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
const live=new LiveVideoCall({
  api,
  onFrame({canvas,width,contentHeight,stamp,meta,layout}) {
    if(frozen||drawing||submitting||!joined)return;
    if(ui.scene.width!==width||ui.scene.height!==contentHeight){
      ui.scene.width=width;ui.scene.height=contentHeight;ui.ink.width=width;ui.ink.height=contentHeight;
    }
    scene.drawImage(canvas,0,0,width,contentHeight,0,0,width,contentHeight);
    const epoch=meta?.epoch??serverState.epoch;
    frame=stamp&&Number.isInteger(epoch)&&((epoch&255)===stamp.epoch)?
      {...layout,...meta,id:stamp.id,epoch,rotation:layout.rotation}:null;
    annotations=meta?.annotations??[];
    ui.imageWrap.hidden=false;ui.empty.hidden=true;
    const fit=fitRect(ui.stage.clientWidth,ui.stage.clientHeight,width,contentHeight);
    Object.assign(ui.imageWrap.style,{left:`${fit.x}px`,top:`${fit.y}px`,width:`${fit.width}px`,height:`${fit.height}px`});
    renderInk();
    if(performance.now()-lastListAt>500){updateList();lastListAt=performance.now();}
    lastFrameAt=performance.now();frameErrorCount=0;
    ui.gestureHint.textContent=frame?'Point or draw. Video freezes only while placing guidance.':'Live video / waiting for frame identity';
  },
  onMetadata(m) {
    if(!frozen&&!drawing&&!submitting&&frame?.id===m.id&&frame.epoch===m.epoch){annotations=m.annotations??[];renderInk();}
  },
  onStatus(status) {
    if(!joined)return;
    if(status==='CONNECTED'){retryCount=0;connection('Connected / WebRTC',true);}
    else if(status==='CONNECTING')connection('Connecting video call...');
    else if(status==='PLAY_REQUIRED'){toast('Tap Connect voice or Reconnect to allow playback.');}
    else if(['FAILED','DISCONNECTED'].includes(status)){
      connection('Reconnecting video...');
      clearTimeout(reconnectTimer);
      if(retryCount<3)reconnectTimer=setTimeout(()=>startVideo(false),1500+retryCount*1000);
      else toast('Video could not connect. Keep ShowMe open and tap Reconnect call.');
    }
  },
  onStats(stats) {
    if(!joined||frozen)return;
    const fps=stats.receivedFps??stats.displayFps;
    ui.frameInfo.textContent=`WebRTC / ${Math.round(fps)} fps / ${ui.scene.width} x ${ui.scene.height} / ${(stats.codec??'video').replace('video/','')}`;
  },
  onAudio(stream) {
    ui.remoteAudio.srcObject=stream;
    ui.remoteAudio.play().catch(()=>toast('Tap the audio button to hear your call.'));
  },
});
async function startVideo(useMicrophone=false){
  if(!joined||stopped||live.connecting||serverState.paused)return;
  retryCount++;
  try{await live.connect({microphone:useMicrophone});}
  catch(error){if(retryCount>=3||useMicrophone)reportError(error);}
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
async function pollState(generation){
  if(!joined||stopped||generation!==loopGeneration)return;
  try{
    serverState=await api('/api/state');
    if(serverState.active===false){endLocal('The owner ended this session.');return;}
    ui.hostTitle.textContent=`${serverState.hostName||'Your helper'}’s view`;
    ui.modeBadge.textContent=serverState.secure?'LOCAL HTTPS':'LOCAL WI-FI';
    if(live.connected)connection(serverState.paused?'Camera paused':serverState.tracking?'Connected / live':'Live / scanning surface',!serverState.paused);
    ui.cameraPaused.hidden=!serverState.paused;
    if(!serverState.paused&&serverState.videoState==='READY'&&!live.connecting)startVideo(false);
    ui.count.textContent=String(serverState.annotations??0);
    if(frame&&serverState.epoch!==frame.epoch){
      drawing=null;annotations=[];frame=null;frozen=false;live.frozen=false;manualFreeze=false;freezePromise=null;
      renderInk();updateFreezeUi();ui.imageWrap.hidden=true;ui.empty.hidden=false;
      toast('The camera session changed. Waiting for a fresh view.');
    }
    if(frozen&&performance.now()-freezeAt>55000&&!submitting){await resumeLive();toast('Frozen frame expired. Live view resumed to keep placement reliable.');}
    if(!live.microphone&&!audioConnecting){
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
    const generation=loopGeneration;startVideo(false);pollState(generation);
  }catch(error){ui.joinError.textContent=error.name==='AbortError'?'Cannot reach the phone. Check that both devices are on the same Wi-Fi or hotspot.':error.message;}
  finally{joining=false;ui.join.disabled=false;}
}
function updateFreezeUi(){
  ui.frameStatus.hidden=!frozen;
  ui.freeze.textContent=frozen?'▶ Resume live view':'Ⅱ Freeze frame';
  ui.gestureHint.textContent=submitting?'Attaching your guidance to the physical surface…':frozen?'Frozen image. Draw on a clear, mapped surface.':'Choose a tool, then point into their view.';
}
function holdFrame(manual=false){
  if(!frame||!joined||performance.now()-lastFrameAt>3000)return Promise.reject(new Error('Wait for a current, identifiable video frame first.'));
  if(manual)manualFreeze=true;
  if(frozen)return freezePromise||Promise.resolve();
  frozen=true;live.frozen=true;freezeAt=performance.now();updateFreezeUi();
  // The screenshot is exactly the already displayed video frame, without the ink overlay.
  const jpeg=ui.scene.toDataURL('image/jpeg',.90).split(',')[1];
  const captured=frame;
  freezePromise=api('/api/freeze',{frameId:captured.id,epoch:captured.epoch,jpeg},{timeout:7000}).then(result=>{
    if(!frozen||frame?.id!==captured.id)throw new Error('Video resumed before the annotation frame was ready.');
    if(Array.isArray(result.annotations))annotations=result.annotations;
    renderInk();return result;
  });
  freezePromise.catch(()=>{});
  return freezePromise;
}
async function resumeLive(){
  drawing=null;frozen=false;live.frozen=false;manualFreeze=false;freezePromise=null;updateFreezeUi();renderInk();
  if(joined)await api('/api/resume',{}).catch(()=>{});
}
ui.freeze.addEventListener('click',async()=>{
  if(submitting||drawing)return;
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
async function stopVoice(notify=true){
  live.setMuted(true);audioMuted=true;
  ui.remoteAudio.pause();ui.voiceState.textContent='Muted';
  if(notify&&joined)await api('/api/voice-stop',{}).catch(()=>{});
}
async function connectVoice(){
  if(!joined||audioConnecting)return;
  if(live.microphone){
    audioMuted=!audioMuted;live.setMuted(audioMuted);
    ui.voice.textContent=audioMuted?'Unmute microphone':'Mute microphone';
    ui.voiceState.textContent=audioMuted?'Muted':'Connected';
    if(ui.remoteAudio.srcObject)ui.remoteAudio.play().catch(()=>{});
    return;
  }
  audioConnecting=true;ui.voice.disabled=true;
  try{
    if(!isSecureContext||!navigator.mediaDevices?.getUserMedia){
      if(!serverState.voiceEnabled){toast('Ask the camera owner to enable Voice. Your browser microphone needs trusted HTTPS.');return;}
      await startVideo(false);
      ui.voiceState.textContent='Listen-only';ui.voice.textContent='Play call audio';
    }else{
      await startVideo(true);
      ui.voiceState.textContent=live.microphone?'Connected':'Off';
      ui.voice.textContent=live.microphone?'Mute microphone':'Connect voice';
    }
    if(ui.remoteAudio.srcObject)await ui.remoteAudio.play();
  }catch(error){reportError(error);}
  finally{audioConnecting=false;ui.voice.disabled=false;}
}
ui.voice.addEventListener('click',connectVoice);
$('reconnect')?.addEventListener('click',()=>{retryCount=0;startVideo(false);});
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
  live.disconnect();clearTimeout(reconnectTimer);stopVoice(false);drawing=null;frozen=false;manualFreeze=false;submitting=false;connection('Session ended');
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
window.addEventListener('pagehide',()=>{live.disconnect();});
ui.joinDialog.showModal();
if(!token)ui.joinError.textContent='Open the invitation link shared by the camera owner. This page alone cannot join a session.';
