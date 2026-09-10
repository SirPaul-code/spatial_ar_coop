import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

// Browser integration fixture only. It is not a substitute for a real ARCore device test.
const web=fileURLToPath(new URL('../web/',import.meta.url));
const artifacts=fileURLToPath(new URL('../test-results/',import.meta.url));
await mkdir(artifacts,{recursive:true});
const browser=await chromium.launch({headless:true});
const generator=await browser.newPage();
const jpeg=Buffer.from(await generator.evaluate(()=>{
  const c=document.createElement('canvas');c.width=640;c.height=480;const x=c.getContext('2d');
  x.fillStyle='#172f39';x.fillRect(0,0,640,480);x.strokeStyle='#456670';x.lineWidth=2;
  for(let n=0;n<650;n+=40){x.beginPath();x.moveTo(n,0);x.lineTo(n,480);x.stroke();}
  for(let n=0;n<490;n+=40){x.beginPath();x.moveTo(0,n);x.lineTo(640,n);x.stroke();}
  x.fillStyle='#67816a';x.fillRect(200,120,240,180);x.strokeStyle='#8ff1c6';x.strokeRect(200,120,240,180);
  x.fillStyle='#fff';x.font='14px sans-serif';x.fillText('TEST FIXTURE / NOT DEVICE FOOTAGE',150,420);
  return c.toDataURL('image/jpeg').split(',')[1];
}), 'base64');
await generator.close();
const token='showme-browser-test-token-123456789';
let id=40,annotations=[],frozenId=null,draws=[],rejectNext=false;
const errors=[];
const server=createServer(async(req,res)=>{
  try{
    const url=new URL(req.url,'http://localhost');
    const json=(data,status=200)=>{res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(data));};
    if(!url.pathname.startsWith('/api/')){
      const name=url.pathname==='/'?'index.html':url.pathname.slice(1);
      if(!['index.html','app.js','geometry.mjs','style.css'].includes(name)){res.writeHead(404);res.end();return;}
      const body=await readFile(web+name);res.writeHead(200,{'content-type':name.endsWith('.css')?'text/css':name.endsWith('.html')?'text/html':'text/javascript'});res.end(body);return;
    }
    if(req.headers.authorization!==`Bearer ${token}`){json({ok:false,code:'SESSION_ENDED',message:'Invalid invitation'},401);return;}
    const chunks=[];for await(const chunk of req)chunks.push(chunk);
    const body=chunks.length?JSON.parse(Buffer.concat(chunks).toString()):{};
    const state={ok:true,active:true,paused:false,tracking:true,trackingMessage:'Surface tracking active',epoch:3,hostName:'Test camera',annotations:annotations.length,voiceEnabled:false,secure:false};
    if(url.pathname==='/api/join'||url.pathname==='/api/state'){json(state);return;}
    if(url.pathname==='/api/frame'){
      const meta=Buffer.from(JSON.stringify({id:++id,epoch:3,width:640,height:480,rotation:90,ageMs:0,depthPoints:5000,annotations}));
      const prefix=Buffer.alloc(4);prefix.writeUInt32BE(meta.length);
      res.writeHead(200,{'content-type':'application/octet-stream','cache-control':'no-store'});res.end(Buffer.concat([prefix,meta,jpeg]));return;
    }
    if(url.pathname==='/api/freeze'){frozenId=body.frameId;assert.equal(body.epoch,3);json({ok:true});return;}
    if(url.pathname==='/api/resume'||url.pathname==='/api/voice-stop'||url.pathname==='/api/leave'){json({ok:true});return;}
    if(url.pathname==='/api/draw'){
      if(body.action==='clear'){annotations=[];json({ok:true});return;}
      if(body.action==='undo'){annotations.pop();json({ok:true});return;}
      if(body.action==='remove'){annotations=annotations.filter(a=>a.id!==body.id);json({ok:true});return;}
      assert.equal(body.frameId,frozenId,'drawing must reference the exact frozen frame');
      assert.equal(body.epoch,3);
      assert.ok(body.points.every(p=>p.length===2&&p.every(v=>Number.isFinite(v)&&v>=0&&v<=1)));
      if(rejectNext){rejectNext=false;json({ok:false,code:'NO_SURFACE',message:'No reliable depth under this drawing.'});return;}
      draws.push(body);
      const a={id:`drawing-${draws.length}`,tool:body.tool,color:body.color,label:body.label,points:body.points,verified:false};
      annotations.push(a);json({ok:true,id:a.id,frameId:body.frameId,annotations});return;
    }
    json({ok:false,message:'Unknown fixture route'},404);
  }catch(error){errors.push(error);res.writeHead(500);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const address=`http://127.0.0.1:${server.address().port}/#${token}`;
async function waitUntil(condition){for(let n=0;n<100;n++){if(condition())return;await new Promise(r=>setTimeout(r,50));}throw new Error('Timed out waiting for fixture action');}
try{
  const desktop=await browser.newContext({viewport:{width:1440,height:960}});
  const page=await desktop.newPage();page.on('pageerror',e=>errors.push(e));page.on('dialog',d=>d.accept());
  await page.goto(address);await page.screenshot({path:artifacts+'desktop-invite.png',fullPage:true});
  await page.locator('#name').fill('Alex');await page.locator('#join').click();
  await page.locator('#imageWrap').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.querySelector('#connection').textContent.includes('live'));
  const canvas=page.locator('#ink');let bounds=await canvas.boundingBox();
  await canvas.click({position:{x:bounds.width*.3,y:bounds.height*.6}});
  await waitUntil(()=>draws.length===1);
  assert.ok(Math.abs(draws[0].points[0][0]-.3)<.01);assert.ok(Math.abs(draws[0].points[0][1]-.6)<.01);
  await page.locator('#freeze').click();await page.locator('#frameStatus').waitFor({state:'visible'});
  await page.waitForTimeout(180);const held=frozenId;
  await page.locator('[data-tool="arrow"]').click();
  await page.locator('#label').fill('<img src=x onerror=alert(1)>');
  bounds=await canvas.boundingBox();
  await page.mouse.move(bounds.x+bounds.width*.2,bounds.y+bounds.height*.3);await page.mouse.down();
  await page.mouse.move(bounds.x+bounds.width*.7,bounds.y+bounds.height*.55,{steps:8});await page.mouse.up();
  await waitUntil(()=>draws.length===2);
  assert.equal(draws[1].frameId,held);assert.equal(draws[1].tool,'arrow');assert.equal(draws[1].points.length,12);
  await page.waitForFunction(()=>document.querySelectorAll('.annotation-item').length===2);
  assert.equal(await page.locator('.annotation-list img').count(),0);
  await page.screenshot({path:artifacts+'desktop-guidance.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.locator('#clear').click();await waitUntil(()=>annotations.length===0);
  await desktop.close();
  const mobile=await browser.newContext({viewport:{width:393,height:852},isMobile:true,hasTouch:true,deviceScaleFactor:2});
  const phone=await mobile.newPage();phone.on('pageerror',e=>errors.push(e));
  await phone.goto(address);await phone.locator('#join').click();await phone.locator('#imageWrap').waitFor({state:'visible'});
  await phone.waitForFunction(()=>document.querySelector('#connection').textContent.includes('live'));
  await phone.locator('#ink').tap();await waitUntil(()=>draws.length===3);
  await phone.waitForTimeout(250);await phone.screenshot({path:artifacts+'mobile-guidance.png',fullPage:true});
  assert.equal(await phone.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  rejectNext=true;await phone.locator('#ink').tap();
  await phone.waitForFunction(()=>document.querySelector('#toast').textContent.includes('No reliable depth'));
  assert.equal(draws.length,3,'a rejected drawing must not become an authoritative annotation');
  await mobile.close();
  assert.deepEqual(errors,[]);
  console.log('Browser smoke passed: desktop/mobile join, exact-frame pin/arrow, normalized pixels, rejection, labels, clear, no horizontal overflow.');
}finally{await browser.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
