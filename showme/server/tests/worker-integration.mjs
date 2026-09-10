import {Miniflare} from 'miniflare';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const secret='test-install-signing-secret-not-production';
const mf=new Miniflare({modules:true,scriptPath:root+'src/worker.mjs',compatibilityDate:'2026-08-06',
  durableObjects:{ROOMS:{className:'ShowMeRoom',useSQLite:true}},bindings:{ROOM_CREATE_KEY:secret,MAX_INSTALLATIONS_PER_DAY:'10',MAX_INSTALLATIONS_PER_IP_PER_DAY:'3',MAX_ROOMS_PER_DAY:'3',MAX_ROOMS_PER_INSTALL_PER_DAY:'3',SESSION_SECONDS:'300'},
  serviceBindings:{ASSETS:async request=>{
    const path=new URL(request.url).pathname;const allowed=['index.html','welcome.html','app.js','style.css','live-video.mjs','geometry.mjs','remote-session.mjs','control-rpc.mjs'];
    if(!allowed.includes(path.slice(1)))return new Response('Not found',{status:404});
    return new Response(await readFile(root+'public'+path),{headers:{'Content-Type':path.endsWith('.html')?'text/html':'text/javascript'}});
  }}});
const base='https://showme.test';const sockets=[];
const post=(path,token,body={},extra={})=>mf.dispatchFetch(base+path,{method:'POST',headers:{...(token?{Authorization:`Bearer ${token}`}:{ }),'content-type':'application/json',...extra},body:JSON.stringify(body)});
function reader(socket){
  const items=[];const waiting=[];
  socket.addEventListener('message',event=>{let data;try{data=JSON.parse(event.data);}catch{data=event.data;}
    const index=waiting.findIndex(job=>job.type===data.type);
    if(index>=0){const job=waiting.splice(index,1)[0];clearTimeout(job.timer);job.resolve(data);}else items.push(data);});
  return type=>{
    const index=items.findIndex(item=>item.type===type);if(index>=0)return Promise.resolve(items.splice(index,1)[0]);
    return new Promise((resolve,reject)=>{const job={type,resolve,timer:setTimeout(()=>reject(new Error(`Missing ${type}: ${JSON.stringify(items)}`)),5000)};waiting.push(job);});
  };
}
async function connect(room,role,cap){
  const response=await mf.dispatchFetch(`${base}/api/rooms/${room}/ws/${role}`,{headers:{Upgrade:'websocket',...(role==='host'?{Authorization:`Bearer ${cap}`}:{'Sec-WebSocket-Protocol':`showme.v1, cap.${cap}`})}});
  assert.equal(response.status,101);const socket=response.webSocket;const next=reader(socket);socket.accept();sockets.push(socket);await next('hello');return {socket,next};
}
try{
  await mf.ready;
  assert.equal((await post('/api/rooms','wrong')).status,401);
  const installRes=await post('/api/installations','',{platform:'android',version:'test'},{'CF-Connecting-IP':'203.0.113.7'});
  assert.equal(installRes.status,200);const install=await installRes.json();assert.equal(install.ok,true);assert.match(install.installationToken,/^v1\./);
  const createdRes=await post('/api/rooms',install.installationToken,{name:'Test owner'});assert.equal(createdRes.status,200);
  const created=await createdRes.json();const room=created.roomId,guestCap=new URL(created.inviteUrl).hash.slice(1);assert.notEqual(guestCap,created.hostToken);
  const page=await mf.dispatchFetch(created.inviteUrl);assert.equal(page.status,200);assert.match(await page.text(),/Join the call/);
  assert.equal((await mf.dispatchFetch(`${base}/api/rooms/${room}/ice`,{headers:{Authorization:`Bearer ${guestCap}`,'x-showme-viewer':'helper001'}})).status,403);
  const host=await connect(room,'host',created.hostToken);const guest=await connect(room,'guest',guestCap);
  guest.socket.send(JSON.stringify({type:'join',viewerId:'helper001',name:'Sam'}));
  assert.equal((await host.next('join-request')).name,'Sam');await guest.next('waiting');
  guest.socket.send(JSON.stringify({type:'offer',id:'request001',sdp:'v=0\r\n'}));await guest.next('error');
  host.socket.send(JSON.stringify({type:'approve',viewerId:'helper001'}));await guest.next('approved');
  guest.socket.send(JSON.stringify({type:'offer',id:'request002',sdp:'v=0\r\n'}));
  const offer=await host.next('offer');assert.equal(offer.viewerId,'helper001');assert.equal(offer.id,'request002');
  host.socket.send(JSON.stringify({type:'answer',id:'request002',sdp:'v=0\r\n',ok:true,video:{width:640,height:480}}));
  assert.equal((await guest.next('answer')).id,'request002');
  assert.equal((await mf.dispatchFetch(`${base}/api/rooms/${room}/ice`,{headers:{Authorization:`Bearer ${guestCap}`,'x-showme-viewer':'helper001'}})).status,503);
  assert.equal((await post(`/api/rooms/${room}/end`,guestCap)).status,404);
  await post(`/api/rooms/${room}/end`,created.hostToken);await guest.next('ended');
  assert.equal((await mf.dispatchFetch(`${base}/api/rooms/${room}/ice`,{headers:{Authorization:`Bearer ${created.hostToken}`}})).status,401);
  assert.equal((await post('/api/rooms',install.installationToken)).status,200);assert.equal((await post('/api/rooms',install.installationToken)).status,200);
  assert.equal((await post('/api/rooms',install.installationToken)).status,429);
  const denied=await mf.dispatchFetch(base+'/api/health',{headers:{Origin:'https://untrusted.test'}});assert.equal(denied.status,403);
  assert.equal((await mf.dispatchFetch(base+'/setup')).status,410);
  console.log('Worker integration passed: zero-config installation, signed installation capability, quotas, owner approval, SDP exchange, room revocation.');
}finally{for(const socket of sockets)try{socket.close();}catch{}await mf.dispose();}
