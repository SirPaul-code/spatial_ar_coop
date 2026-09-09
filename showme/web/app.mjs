import {palette,normalizedPoint,shapePoints,decodePacket,requestId} from './protocol.mjs';
const $=id=>document.getElementById(id), canvas=$('scene'),ctx=canvas.getContext('2d',{alpha:false});
const token=new URLSearchParams(location.hash.slice(1)).get('token')||'';
const client=requestId();
let ws=null,connected=false,connecting=false,retries=0,retryTimer=0,pullTimer=0,startedAt=0;
let frame=null,bitmap=null,tool='pin',color='mint',drawing=null,pending=null,frozen=false,freezeAt=0,cameraLive=false,decoding=false,requestInFlight=false;
let toastTimer=0,pc=null,micStream=null,remoteIce=[],recorder=null,recordStream=null,recordChunks=[];
function send(message){if(ws?.readyState===WebSocket.OPEN){ws.send(JSON.stringify(message));return true;}return false;}
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4500);}
function notice(title,text){$('sceneNotice').hidden=false;$('noticeTitle').textContent=title;$('noticeText').textContent=text;}
function status(text,live=false){$('connectionText').textContent=text;$('connectionDot').classList.toggle('live',live);}
function pullSoon(delay=100){clearTimeout(pullTimer);if(connected&&!drawing&&!pending&&!frozen)pullTimer=setTimeout(()=>{if(requestInFlight)return;requestInFlight=send({type:'pull',since:frame?.frameId||''});},delay);}
function badge(){const b=$('liveBadge');b.textContent=frozen||drawing?'FRAME HELD':cameraLive?'LIVE':'WAITING';b.className=`badge ${frozen||drawing?'frozen':cameraLive?'live':''}`;$('freeze').setAttribute('aria-pressed',String(frozen));$('freeze').querySelector('span').textContent=frozen?'Resume':'Freeze';}
function join(){
  if(!token){$('joinMessage').textContent='This link is missing its private session code. Ask the camera owner to use Invite and send a new link.';return;}
  if(connecting||connected)return;connecting=true;cameraLive=false;status('Connecting');$('joinButton').disabled=true;
  const protocol=location.protocol==='https:'?'wss:':'ws:';
  const url=`${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}&client=${client}&name=${encodeURIComponent($('name').value.trim()||'Helper')}`;
  const socket=new WebSocket(url);ws=socket;socket.binaryType='arraybuffer';
  socket.onmessage=async event=>{
    if(ws!==socket)return;
    if(event.data instanceof ArrayBuffer){requestInFlight=false;await acceptFrame(event.data);pullSoon();return;}
    let m;try{m=JSON.parse(event.data);}catch{return;}
    switch(m.type){
      case 'waiting':status('Waiting for approval');$('joinMessage').textContent=m.message;break;
      case 'ready':connecting=false;connected=true;retries=0;startedAt=performance.now();$('gate').hidden=true;notice('Waiting for camera','Ask the camera owner to keep a textured surface in view and move slowly.');status('Connected',true);$('hint').textContent='Tap to pin a point. Drag to draw on the exact frame you see.';pullSoon(0);break;
      case 'paused':requestInFlight=false;cameraLive=false;notice('Camera paused',m.message||'Waiting for tracking');badge();pullSoon(400);break;
      case 'live':pullSoon(0);break;
      case 'idle':requestInFlight=false;pullSoon(90);break;
      case 'ack':if(pending&&pending.requestId===m.requestId){if(frozen&&frame)frame.annotations.push({id:m.annotationId,tool:pending.tool,color:pending.color,label:pending.label,points:pending.points,verified:false});pending=null;updateCount(m.count);toast('Pinned to the real world');if(!frozen)send({type:'release'});redraw();pullSoon(0);}break;
      case 'error':toast(m.message||'Could not place this mark');if(pending&&(!m.requestId||m.requestId===pending.requestId)){pending=null;if(!frozen)send({type:'release'});redraw();pullSoon(0);}break;
      case 'cleared':if(frame)frame.annotations=[];pending=null;updateCount(0);redraw();pullSoon(0);break;
      case 'changed':updateCount(m.count);if(frozen)resume();else pullSoon(0);break;
      case 'chat':appendMessage(m.name,m.text);break;
      case 'denied':retries=6;$('joinMessage').textContent='The camera owner declined access.';break;
      case 'voiceAvailable':toast('Camera microphone is ready. Press the audio button to listen.');break;
      case 'voiceAnswer':if(pc){try{await pc.setRemoteDescription({type:'answer',sdp:m.sdp});for(const c of remoteIce)await pc.addIceCandidate(c);remoteIce=[];}catch{toast('Audio negotiation failed');stopAudio(false);}}break;
      case 'voiceIce':if(pc){const c={candidate:m.candidate,sdpMid:m.sdpMid,sdpMLineIndex:m.sdpMLineIndex};if(pc.remoteDescription)pc.addIceCandidate(c).catch(()=>{});else if(remoteIce.length<128)remoteIce.push(c);}break;
      case 'voiceError':toast(m.message);stopAudio(false);break;
      case 'voiceClosed':stopAudio(false);break;
    }
  };
  socket.onerror=()=>{};
  socket.onclose=()=>{if(ws!==socket)return;connecting=false;connected=false;cameraLive=false;requestInFlight=false;drawing=null;pending=null;frozen=false;stopAudio(false);clearTimeout(pullTimer);status('Disconnected');badge();$('joinButton').disabled=false;
    if(startedAt){notice('Connection lost','Your existing marks remain on the camera phone. Reconnecting…');if(retries<6){retryTimer=setTimeout(join,Math.min(8000,1000*2**retries++));}else{notice('Session unavailable','Ask the camera owner to resume ShowMe, check Wi-Fi, or send a new link.');$('gate').hidden=false;$('joinMessage').textContent='Connection lost. Check Wi-Fi and try joining again.';}}
    else{$('joinMessage').textContent='Could not connect. Join the same Wi-Fi or hotspot, keep ShowMe open, and check that this is the latest invitation.';}
  };
}
async function acceptFrame(bytes){
  if(decoding)return;decoding=true;
  try{
    const {metadata,jpeg}=decodePacket(bytes);const blob=new Blob([jpeg],{type:'image/jpeg'});
    const image=await decodeImage(blob);
    if(drawing||pending||frozen){image.close?.();return;}
    bitmap?.close?.();bitmap=image;frame=metadata;canvas.width=metadata.width;canvas.height=metadata.height;
    cameraLive=metadata.tracking===true;$('sceneNotice').hidden=cameraLive;$('trackingChip').textContent=cameraLive?'AR TRACKING':'TRACKING PAUSED';$('depthChip').textContent=`${metadata.depthPoints||0} depth points`;
    updateCount(metadata.count);updateMarkList();badge();redraw();
  }catch{toast('Frame could not be decoded. Waiting for the next frame.');}finally{decoding=false;}
}
function decodeImage(blob){if(typeof createImageBitmap==='function')return createImageBitmap(blob);return new Promise((resolve,reject)=>{const image=new Image(),url=URL.createObjectURL(blob);image.onload=()=>{URL.revokeObjectURL(url);resolve(image);};image.onerror=()=>{URL.revokeObjectURL(url);reject(Error('Invalid image'));};image.src=url;});}
function redraw(){
  if(!bitmap||!frame)return;ctx.drawImage(bitmap,0,0,canvas.width,canvas.height);
  for(const a of frame.annotations)paintMark(a,false);
  if(drawing){const points=shapePoints(tool,drawing.points,canvas.width,canvas.height);paintMark({tool,color,label:$('markLabel').value,points},true);}
  if(pending)paintMark(pending,true);
  ctx.save();ctx.font=`${Math.max(12,canvas.width*.016)}px sans-serif`;ctx.textAlign='right';ctx.fillStyle='#ffffff99';ctx.shadowColor='#000';ctx.shadowBlur=4;ctx.fillText('ShowMe / spatial guidance',canvas.width-16,canvas.height-18);ctx.restore();
}
function paintMark(a,draft){
  if(!Array.isArray(a.points)||!a.points.length)return;
  const points=a.points.map(p=>Array.isArray(p)&&p.length===2&&p.every(Number.isFinite)?[p[0]*canvas.width,p[1]*canvas.height]:null);const first=points.find(Boolean);if(!first)return;
  const unit=Math.max(1,canvas.width/600);ctx.save();ctx.strokeStyle=palette[a.color]||palette.mint;ctx.fillStyle=ctx.strokeStyle;ctx.lineWidth=3*unit;ctx.lineCap='round';ctx.lineJoin='round';ctx.shadowColor='#0009';ctx.shadowBlur=5*unit;ctx.setLineDash(draft?[5*unit,5*unit]:[]);
  if(a.tool==='pin'||a.tool==='pointer'){ctx.beginPath();ctx.arc(first[0],first[1],13*unit,0,Math.PI*2);ctx.stroke();ctx.beginPath();ctx.arc(first[0],first[1],3*unit,0,Math.PI*2);ctx.fill();ctx.globalAlpha=.2;ctx.lineWidth=8*unit;ctx.beginPath();ctx.arc(first[0],first[1],18*unit,0,Math.PI*2);ctx.stroke();ctx.globalAlpha=1;}
  else{ctx.beginPath();let started=false;for(const p of points){if(!p){started=false;continue;}if(!started){ctx.moveTo(...p);started=true;}else ctx.lineTo(...p);}ctx.stroke();}
  const label=(a.label||(a.tool==='pin'?a.id||'POINT':'')).slice(0,60);
  if(label){ctx.setLineDash([]);ctx.font=`600 ${13*unit}px sans-serif`;const text=label+(a.verified?' / VERIFIED':'');const w=Math.min(ctx.measureText(text).width+20*unit,canvas.width-20);const x=Math.max(8,Math.min(first[0]+22*unit,canvas.width-w-8)),y=Math.max(8,Math.min(first[1]-17*unit,canvas.height-40*unit));ctx.fillStyle='#101c2ae8';ctx.fillRect(x,y,w,32*unit);ctx.fillStyle='#f3fafc';ctx.fillText(text,x+10*unit,y+21*unit,w-15*unit);}
  ctx.restore();
}
function updateCount(count){$('markCount').textContent=`${count||0} mark${count===1?'':'s'}`;}
function updateMarkList(){const list=$('markList');list.replaceChildren();for(const a of (frame?.annotations||[]).filter(a=>a.tool!=='pointer').slice(-12).reverse()){const item=document.createElement('div');item.className='mark-item';const text=document.createElement('span');text.textContent=a.label||`${a.tool.toUpperCase()} / ${a.id}`;const button=document.createElement('button');button.textContent='×';button.setAttribute('aria-label',`Remove ${a.label||a.id}`);button.onclick=()=>send({type:'remove',id:a.id});item.append(text,button);list.append(item);}}
function point(event){return frame?normalizedPoint(event.clientX,event.clientY,canvas.getBoundingClientRect(),frame.width,frame.height):null;}
canvas.addEventListener('pointerdown',event=>{
  if(event.button!==0||!connected||!frame||!cameraLive||pending||drawing)return;const p=point(event);if(!p)return;
  event.preventDefault();canvas.setPointerCapture(event.pointerId);clearTimeout(pullTimer);
  drawing={points:[p],frameId:frame.frameId,pointer:event.pointerId};send({type:'hold',frameId:frame.frameId});badge();redraw();
});
canvas.addEventListener('pointermove',event=>{if(!drawing||drawing.pointer!==event.pointerId)return;const p=point(event);if(!p)return;const last=drawing.points.at(-1);if(Math.hypot(p[0]-last[0],p[1]-last[1])<.0015)return;if(tool==='pen'){drawing.points.push(p);if(drawing.points.length>2048)drawing.points=drawing.points.filter((_,i)=>i%2===0);}else drawing.points=[drawing.points[0],p];redraw();});
canvas.addEventListener('pointerup',event=>{
  if(!drawing||drawing.pointer!==event.pointerId)return;event.preventDefault();const source=drawing;drawing=null;
  const points=shapePoints(tool,source.points,frame.width,frame.height);
  if(!points.length||!['pin','pointer'].includes(tool)&&points.length<2){toast('Drag a little farther to draw this shape.');if(!frozen)send({type:'release'});badge();redraw();pullSoon(0);return;}
  pending={type:'draw',requestId:requestId(),frameId:source.frameId,tool,color,label:$('markLabel').value.trim(),points};send(pending);redraw();badge();
  const request=pending.requestId;setTimeout(()=>{if(pending?.requestId===request){pending=null;toast('No placement confirmation yet. Resume live view to check before retrying.');resume();}},6000);
});
canvas.addEventListener('pointercancel',()=>{drawing=null;if(!frozen)send({type:'release'});redraw();badge();pullSoon(0);});
function resume(){frozen=false;drawing=null;send({type:'release'});badge();redraw();pullSoon(0);$('hint').textContent='Tap to pin a point. Drag to draw on the exact frame you see.';}
$('freeze').onclick=()=>{if(!frame||!connected)return;if(frozen){resume();return;}frozen=true;freezeAt=performance.now();clearTimeout(pullTimer);send({type:'hold',frameId:frame.frameId});badge();$('hint').textContent='Frame held for up to 40 seconds. Marks still attach to the real surface.';};
for(const button of document.querySelectorAll('[data-tool]'))button.onclick=()=>{if(drawing||pending)return;tool=button.dataset.tool;for(const b of document.querySelectorAll('[data-tool]')){b.classList.toggle('selected',b===button);b.setAttribute('aria-pressed',String(b===button));}$('hint').textContent=tool==='pointer'?'Point to a surface for three seconds. Other tools stay anchored.':tool==='pin'?'Tap an exact physical point to pin it.':'Drag across the image. We hold the frame while you draw.';};
for(const button of document.querySelectorAll('[data-color]'))button.onclick=()=>{color=button.dataset.color;for(const b of document.querySelectorAll('[data-color]')){b.classList.toggle('selected',b===button);b.setAttribute('aria-pressed',String(b===button));}};
$('joinForm').onsubmit=e=>{e.preventDefault();clearTimeout(retryTimer);retries=0;join();};
$('undo').onclick=()=>{if(connected)send({type:'undo'});};
$('clear').onclick=()=>{if(connected&&confirm('Remove every AR mark from this session?'))send({type:'clear'});};
$('fullscreen').onclick=()=>{if(document.fullscreenElement)document.exitFullscreen?.();else $('stage').requestFullscreen?.().catch(()=>toast('Fullscreen is not available in this browser.'));};
function appendMessage(name,text){const messages=$('messages');messages.querySelector('.empty-note')?.remove();const item=document.createElement('div');item.className='message';const author=document.createElement('strong');author.textContent=String(name||'Helper').slice(0,32);const body=document.createElement('span');body.textContent=String(text||'').slice(0,300);item.append(author,body);messages.append(item);while(messages.childElementCount>60)messages.firstElementChild.remove();messages.scrollTop=messages.scrollHeight;}
$('chatForm').onsubmit=e=>{e.preventDefault();const text=$('chat').value.trim();if(!text||!connected)return;send({type:'chat',text});$('chat').value='';};
function download(blob,name){const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),30000);}
$('snapshot').onclick=()=>{if(!frame)return;canvas.toBlob(blob=>{if(blob)download(blob,`ShowMe-${Date.now()}.png`);});};
$('record').onclick=()=>{
  if(recorder?.state==='recording'){recorder.stop();return;}
  if(!frame)return toast('Connect to the camera first.');
  if(!canvas.captureStream||typeof MediaRecorder==='undefined')return toast('Screen recording is not supported here. Use your device screen recorder.');
  try{recordStream=canvas.captureStream(20);recordChunks=[];const type=['video/webm;codecs=vp9','video/webm;codecs=vp8','video/mp4'].find(t=>MediaRecorder.isTypeSupported(t));recorder=new MediaRecorder(recordStream,type?{mimeType:type}:{});recorder.ondataavailable=e=>{if(e.data.size)recordChunks.push(e.data);};recorder.onstop=()=>{download(new Blob(recordChunks,{type:recorder.mimeType}),`ShowMe-${Date.now()}.${recorder.mimeType.includes('mp4')?'mp4':'webm'}`);recordStream.getTracks().forEach(t=>t.stop());$('record').classList.remove('recording');$('recordBadge').hidden=true;recorder=null;recordChunks=[];};recorder.start(1000);$('record').classList.add('recording');$('recordBadge').hidden=false;toast('Recording this view without audio. Press Record again to save.');setTimeout(()=>{if(recorder?.state==='recording')recorder.stop();},180000);}catch{toast('Recording could not start. Use your device screen recorder.');recordStream?.getTracks().forEach(t=>t.stop());}
};
async function startAudio(){
  if(pc){stopAudio(true);return;}if(!connected)return toast('Join the camera session first.');
  if(typeof RTCPeerConnection==='undefined')return toast('Audio is not supported in this browser.');
  try{
    const connection=new RTCPeerConnection({iceServers:[]});pc=connection;let offerSent=false;const outgoing=[];
    connection.onicecandidate=e=>{if(e.candidate){const message={type:'voiceIce',candidate:e.candidate.candidate,sdpMid:e.candidate.sdpMid,sdpMLineIndex:e.candidate.sdpMLineIndex};if(offerSent)send(message);else outgoing.push(message);}};
    connection.ontrack=e=>{$('remoteAudio').srcObject=e.streams[0]||new MediaStream([e.track]);$('remoteAudio').play().catch(()=>toast('Tap audio again to allow playback.'));};
    connection.onconnectionstatechange=()=>{if(pc!==connection)return;const live=connection.connectionState==='connected';$('audio').classList.toggle('active',live);if(live)$('audioHint').textContent=micStream?'Voice connected. Tap the microphone to end audio.':'Listening to the camera owner. Browser microphone needs HTTPS.';else if(['failed','closed'].includes(connection.connectionState))stopAudio(false);};
    if(window.isSecureContext&&navigator.mediaDevices?.getUserMedia){try{micStream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});for(const track of micStream.getAudioTracks())connection.addTrack(track,micStream);}catch{toast('Microphone not available. Connecting in listen-only mode.');}}
    if(!micStream)connection.addTransceiver('audio',{direction:'recvonly'});
    const offer=await connection.createOffer();await connection.setLocalDescription(offer);send({type:'voiceOffer',sdp:offer.sdp});offerSent=true;outgoing.forEach(send);$('audioHint').textContent='Connecting audio. The camera owner must enable their microphone.';
  }catch{toast('Audio failed. Camera and AR marks are still available.');stopAudio(false);}
}
function stopAudio(notify){const old=pc;pc=null;old?.close();micStream?.getTracks().forEach(t=>t.stop());micStream=null;remoteIce=[];$('remoteAudio').srcObject=null;$('audio').classList.remove('active');$('audioHint').textContent='The owner can enable their mic. Local HTTP supports listening; browser talk requires HTTPS.';if(notify)send({type:'voiceStop'});}
$('audio').onclick=startAudio;
setInterval(()=>{if(connected){const seconds=Math.floor((performance.now()-startedAt)/1000);$('clock').textContent=`${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`;send({type:'ping'});}if(frozen&&performance.now()-freezeAt>40000){toast('Live view resumed because the held frame expired.');resume();}},1000);
window.addEventListener('pagehide',()=>{clearTimeout(retryTimer);stopAudio(true);ws?.close();if(recorder?.state==='recording')recorder.stop();});
if(!token)$('joinMessage').textContent='Open the complete invitation link from the camera owner. It contains a private session code.';
