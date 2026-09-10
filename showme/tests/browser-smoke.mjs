import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

// Real browser WebRTC encoder -> RTP -> decoder. Only camera/depth are fixtures.
// Never report these measurements as Android device performance/physical accuracy.
const web=fileURLToPath(new URL('../web/',import.meta.url));
const artifacts=fileURLToPath(new URL('../test-results/',import.meta.url));
await mkdir(artifacts,{recursive:true});
const browser=await chromium.launch({headless:true,
  ...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),
  args:['--disable-background-timer-throttling','--disable-backgrounding-occluded-windows','--disable-renderer-backgrounding',
    '--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required']});
const token='showme-browser-test-token-123456789';
let annotations=[],frozenId=null,draws=[],rejectNext=false,producer=null,legacyPolls=0,callCount=0;
const errors=[];
const server=createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    const json=(data,status=200)=>{res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(data));};
    if(url.pathname==='/producer'){res.writeHead(200,{'content-type':'text/html'});res.end('<!doctype html><canvas id="source" width="480" height="672"></canvas>');return;}
    if(!url.pathname.startsWith('/api/')){
      const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
      if(!['index.html','app.js','geometry.mjs','live-video.mjs','style.css'].includes(name)){res.writeHead(404);res.end();return;}
      const body=await readFile(web+name);res.writeHead(200,{'content-type':name.endsWith('.css')?'text/css':name.endsWith('.html')?'text/html':'text/javascript'});res.end(body);return;
    }
    if(req.headers.authorization!==`Bearer ${token}`){json({ok:false,code:'SESSION_ENDED',message:'Invalid invitation'},401);return;}
    const chunks=[];for await(const chunk of req)chunks.push(chunk);
    const body=chunks.length?JSON.parse(Buffer.concat(chunks).toString()):{};
    const state={ok:true,active:true,paused:false,tracking:true,trackingMessage:'Surface tracking active',epoch:3,hostName:'Test camera',annotations:annotations.length,voiceEnabled:true,secure:false};
    if(url.pathname==='/api/join'||url.pathname==='/api/state'){json(state);return;}
    if(url.pathname==='/api/frame'){legacyPolls++;json({ok:false,message:'Legacy JPEG video forbidden'},410);return;}
    if(url.pathname==='/api/call'){
      callCount++;
      const answer=await producer.evaluate(offer=>window.source.answer(offer),body.sdp);
      json({ok:true,sdp:answer,video:{videoWidth:480,videoHeight:672,contentHeight:640,width:640,height:480,rotation:90,targetFps:30}});return;
    }
    if(url.pathname==='/api/freeze'){
      const observed=await producer.evaluate(id=>window.source.frames.has(id),body.frameId);
      assert.ok(observed,'freeze ID must be a genuinely transmitted video frame, not newest metadata');
      assert.equal(body.epoch,3);assert.ok(body.jpeg?.length>100);
      const dims=await producer.evaluate(async base64=>{const image=new Image();image.src='data:image/jpeg;base64,'+base64;await image.decode();return [image.width,image.height];},body.jpeg);
      assert.ok(dims[0]>=256 && dims[0]<=480,'freeze uses a decodable received resolution');
      assert.ok(Math.abs(dims[1]-Math.round(dims[0]*640/480))<=1,
        `screenshot must exclude the scaled identity footer: ${dims}`);
      frozenId=body.frameId;json({ok:true,id:frozenId,epoch:3,annotations});return;
    }
    if(['/api/resume','/api/voice-stop','/api/call-stop','/api/leave'].includes(url.pathname)){json({ok:true});return;}
    if(url.pathname==='/api/draw'){
      if(body.action==='clear'){annotations=[];await producer.evaluate(a=>window.source.annotations=a,annotations);json({ok:true});return;}
      if(body.action==='undo'){annotations.pop();await producer.evaluate(a=>window.source.annotations=a,annotations);json({ok:true});return;}
      if(body.action==='remove'){annotations=annotations.filter(a=>a.id!==body.id);await producer.evaluate(a=>window.source.annotations=a,annotations);json({ok:true});return;}
      assert.equal(body.frameId,frozenId,'drawing must reference the exact frozen VIDEO frame');
      assert.equal(body.epoch,3);
      assert.ok(body.points.every(p=>p.length===2&&p.every(v=>Number.isFinite(v)&&v>=0&&v<=1)));
      if(rejectNext){rejectNext=false;json({ok:false,code:'NO_SURFACE',message:'No reliable depth under this drawing.'});return;}
      draws.push(body);const a={id:`drawing-${draws.length}`,tool:body.tool,color:body.color,label:body.label,points:body.points,verified:false};
      annotations.push(a);await producer.evaluate(a=>window.source.annotations=a,annotations);
      json({ok:true,id:a.id,frameId:body.frameId,annotations});return;
    }
    json({ok:false,message:'Unknown fixture route'},404);
  }catch(error){errors.push(error);res.writeHead(500);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base=`http://127.0.0.1:${server.address().port}`;
async function waitUntil(condition,message='fixture action'){
  for(let n=0;n<160;n++){if(condition())return;await new Promise(r=>setTimeout(r,50));}
  throw new Error(`Timed out waiting for ${message}; errors: ${errors.map(String).join('; ')}`);
}
try{
  producer=await browser.newPage();await producer.goto(base+'/producer');
  await producer.evaluate(async()=>{
    const {stampBytes}=await import('/live-video.mjs');
    const canvas=document.querySelector('canvas'),ctx=canvas.getContext('2d');
    window.source={pc:null,channel:null,id:1000,frames:new Map(),annotations:[],frameCounter:0};
    const s=window.source;
    const stream=canvas.captureStream(30);
    const audioContext=new AudioContext(),oscillator=audioContext.createOscillator(),gain=audioContext.createGain(),dest=audioContext.createMediaStreamDestination();
    gain.gain.value=.001;oscillator.connect(gain).connect(dest);oscillator.start();await audioContext.resume();
    s.answer=async offer=>{
      s.pc?.close();s.channel=null;
      const pc=new RTCPeerConnection({iceServers:[]});s.pc=pc;
      pc.addTrack(stream.getVideoTracks()[0],stream);
      pc.addTrack(dest.stream.getAudioTracks()[0],dest.stream);
      pc.ondatachannel=e=>{s.channel=e.channel;};
      await pc.setRemoteDescription({type:'offer',sdp:offer});
      await pc.setLocalDescription(await pc.createAnswer());
      await new Promise(resolve=>{
        if(pc.iceGatheringState==='complete'){resolve();return;}
        const done=()=>{clearTimeout(timer);pc.removeEventListener('icegatheringstatechange',change);resolve();};
        const change=()=>{if(pc.iceGatheringState==='complete')done();};const timer=setTimeout(done,4000);pc.addEventListener('icegatheringstatechange',change);
      });
      return pc.localDescription.sdp;
    };
    setInterval(()=>{
      const id=++s.id;s.frameCounter++;
      ctx.fillStyle='#172f39';ctx.fillRect(0,0,480,640);ctx.strokeStyle='#456670';
      for(let n=0;n<640;n+=40){ctx.beginPath();ctx.moveTo(0,n);ctx.lineTo(480,n);ctx.stroke();}
      ctx.fillStyle='#67816a';ctx.fillRect(100,160,240,180);ctx.fillStyle='#8ff1c6';ctx.fillRect((id*4)%430,420,50,14);
      ctx.fillStyle='#fff';ctx.font='14px sans-serif';ctx.fillText(`WEBRTC TEST FIXTURE / FRAME ${id}`,65,570);
      const bytes=stampBytes(id,3);
      for(let row=0;row<2;row++)for(let cell=0;cell<64;cell++){
        const bit=((bytes[cell>>3]>>(7-cell%8))&1)!==0;
        ctx.fillStyle=(bit!==(row===1))?'#fff':'#000';
        const x=Math.floor(cell*480/64),end=Math.floor((cell+1)*480/64);
        ctx.fillRect(x,640+row*16,end-x,16);
      }
      s.frames.set(id,performance.now());while(s.frames.size>240)s.frames.delete(s.frames.keys().next().value);
      if(s.channel?.readyState==='open')s.channel.send(JSON.stringify({id,epoch:3,width:640,height:480,rotation:90,depthAvailable:true,annotations:s.annotations}));
    },1000/30);
  });
  const desktop=await browser.newContext({viewport:{width:1440,height:960},permissions:['microphone']});
  const page=await desktop.newPage();page.on('pageerror',e=>errors.push(e));page.on('dialog',d=>d.accept());
  await page.goto(base+'/#'+token);await page.screenshot({path:artifacts+'desktop-invite.png',fullPage:true});
  await page.locator('#name').fill('Alex');await page.locator('#join').click();
  await page.locator('#imageWrap').waitFor({state:'visible',timeout:25000});
  await page.waitForFunction(()=>document.querySelector('#gestureHint').textContent.startsWith('Point or draw'),{},{timeout:15000});
  await page.waitForFunction(()=>document.querySelector('#frameInfo').textContent.startsWith('WebRTC'),{},{timeout:10000});
  const fpsText=await page.locator('#frameInfo').textContent();console.log('Received real browser WebRTC:',fpsText);
  assert.ok(!fpsText.includes('NaN'));
  const canvas=page.locator('#ink');let bounds=await canvas.boundingBox();
  await canvas.click({position:{x:bounds.width*.3,y:bounds.height*.6}});await waitUntil(()=>draws.length===1,'video-linked pin');
  assert.ok(Math.abs(draws[0].points[0][0]-.3)<.01);assert.ok(Math.abs(draws[0].points[0][1]-.6)<.01);
  await page.waitForTimeout(350);await page.locator('#freeze').click();await waitUntil(()=>frozenId!==draws[0].frameId,'manual freeze');
  const held=frozenId;
  const before=await page.locator('#scene').evaluate(c=>c.toDataURL());
  await page.waitForTimeout(3400);
  assert.equal(await page.locator('#scene').evaluate(c=>c.toDataURL()),before,'freeze must hold an immutable displayed image while RTP continues');
  await page.locator('[data-tool="arrow"]').click();await page.locator('#label').fill('<img src=x onerror=alert(1)>');
  bounds=await canvas.boundingBox();
  await page.mouse.move(bounds.x+bounds.width*.2,bounds.y+bounds.height*.3);await page.mouse.down();
  await page.mouse.move(bounds.x+bounds.width*.7,bounds.y+bounds.height*.55,{steps:8});await page.mouse.up();await waitUntil(()=>draws.length===2,'video-linked arrow');
  assert.equal(draws[1].frameId,held);assert.equal(draws[1].tool,'arrow');
  assert.equal(await page.locator('.annotation-list img').count(),0);
  await page.screenshot({path:artifacts+'desktop-guidance.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.locator('#clear').click();await waitUntil(()=>annotations.length===0);
  const previousCalls=callCount;await page.locator('#reconnect').click();await waitUntil(()=>callCount>previousCalls,'renegotiated video');
  await page.waitForFunction(()=>document.querySelector('#gestureHint').textContent.startsWith('Point or draw'),{},{timeout:15000});
  await page.locator('#voice').click();await page.waitForFunction(()=>document.querySelector('#voice').textContent.includes('Mute microphone'),{},{timeout:20000});
  assert.ok(await page.locator('#remoteAudio').evaluate(a=>a.srcObject?.getAudioTracks().length>0),'call includes received audio');
  await page.locator('#voice').click();assert.equal(await page.locator('#voiceState').textContent(),'Muted');
  await desktop.close();
  const mobile=await browser.newContext({viewport:{width:393,height:852},isMobile:true,hasTouch:true,deviceScaleFactor:2});
  const phone=await mobile.newPage();phone.on('pageerror',e=>errors.push(e));
  await phone.goto(base+'/#'+token);await phone.locator('#join').click();await phone.locator('#imageWrap').waitFor({state:'visible',timeout:25000});
  await phone.waitForFunction(()=>document.querySelector('#gestureHint').textContent.startsWith('Point or draw'),{},{timeout:15000});
  await phone.locator('#ink').tap();await waitUntil(()=>draws.length===3,'mobile pin');
  await phone.waitForTimeout(350);await phone.screenshot({path:artifacts+'mobile-guidance.png',fullPage:true});
  assert.equal(await phone.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  rejectNext=true;await phone.locator('#ink').tap();
  await phone.waitForFunction(()=>document.querySelector('#toast').textContent.includes('No reliable depth'));
  assert.equal(draws.length,3,'rejected depth must not become an authoritative annotation');
  await mobile.close();
  assert.equal(legacyPolls,0,'live video must never fall back to polling JPEGs');
  assert.deepEqual(errors,[]);
  console.log('PASS: real WebRTC video/audio, coded frame ID, exact-frame freeze/drawing, reconnect, mute, desktop/mobile layout, no JPEG live polling.');
}finally{await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
