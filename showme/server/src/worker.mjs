import {ROOM_RE,ID_RE,MAX_SIGNAL_BYTES,MAX_SESSION_SECONDS,json,fail,boundedInt,cleanName,token,digest,sameSecret,bearer,readJson,approvedSignal,normalizeIce} from './policy.mjs';

const SOCKET_PROTOCOL='showme.v1';
const safeSend=(socket,value)=>{try{socket.send(typeof value==='string'?value:JSON.stringify(value));return true;}catch{return false;}};
function secure(response) {
  const headers=new Headers(response.headers);
  headers.set('Referrer-Policy','no-referrer');headers.set('X-Content-Type-Options','nosniff');
  headers.set('X-Frame-Options','DENY');headers.set('Permissions-Policy','camera=(), microphone=(self), geolocation=()');
  headers.set('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
  return new Response(response.body,{status:response.status,headers});
}

export default {
  async fetch(request,env) {
    const url=new URL(request.url),path=url.pathname;
    try {
      const origin=request.headers.get('origin');
      if(origin&&origin!==url.origin)return fail('ORIGIN','Cross-origin access is not permitted.',403);
      if(path==='/api/health')return json({ok:true,service:'ShowMe',protocol:3,
        configured:!!env.ROOM_CREATE_KEY,relayConfigured:!!(env.TURN_KEY_ID&&env.TURN_API_TOKEN)});
      if(path==='/api/rooms'&&request.method==='POST') {
        if(!await sameSecret(bearer(request),env.ROOM_CREATE_KEY))return fail('ACTIVATION_REQUIRED','Connect this phone using your ShowMe activation link.',401);
        const body=await readJson(request);
        // A hard room/day ceiling on top of an unguessable owner activation key bounds demo abuse.
        const quota=env.ROOMS.get(env.ROOMS.idFromName('__creation_quota__'));
        const allowed=await quota.fetch(new Request('https://room.internal/quota',{method:'POST'}));
        if(!allowed.ok)return allowed;
        const roomId=token(18),hostToken=token(24),guestToken=token(24);
        const expiresAt=Date.now()+boundedInt(env.SESSION_SECONDS,1800,300,MAX_SESSION_SECONDS)*1000;
        const room=env.ROOMS.get(env.ROOMS.idFromName(roomId));
        const init=await room.fetch(new Request('https://room.internal/init',{method:'POST',headers:{'content-type':'application/json'},
          body:JSON.stringify({hostHash:await digest(hostToken),guestHash:await digest(guestToken),expiresAt,ownerName:cleanName(body.name)})}));
        if(!init.ok)return fail('CREATE_FAILED','Could not create a session.',503);
        return json({ok:true,roomId,hostToken,expiresAt,
          inviteUrl:`${url.origin}/r/${roomId}#${guestToken}`,
          socketUrl:`${url.origin.replace('https:','wss:').replace('http:','ws:')}/api/rooms/${roomId}/ws/host`});
      }
      const match=path.match(/^\/api\/rooms\/([A-Za-z0-9_-]{24})\/(ws\/(host|guest)|ice|end)$/);
      if(match){
        const room=env.ROOMS.get(env.ROOMS.idFromName(match[1]));
        const forwarded=new URL(request.url);forwarded.pathname='/'+match[2];
        return room.fetch(new Request(forwarded,request));
      }
      if(path.startsWith('/api/'))return fail('NOT_FOUND','Unknown endpoint.',404);
      if(request.method!=='GET'&&request.method!=='HEAD')return fail('METHOD','Unsupported method.',405);
      if(path==='/setup')return secure(await env.ASSETS.fetch(new Request(new URL('/setup.html',url),request)));
      if(path==='/')return secure(await env.ASSETS.fetch(new Request(new URL('/welcome.html',url),request)));
      if(path.startsWith('/r/')){
        if(!ROOM_RE.test(path.slice(3)))return fail('NOT_FOUND','Invalid invitation.',404);
        const response=await env.ASSETS.fetch(new Request(new URL('/index.html',url),request));
        const result=secure(response);result.headers.set('Cache-Control','no-store');return result;
      }
      return secure(await env.ASSETS.fetch(request));
    }catch{return fail('INVALID_REQUEST','Request could not be processed.',400);}
  }
};

/** SQLite-backed Durable Object. Incoming sockets hibernate; no continuous media here. */
export class ShowMeRoom {
  constructor(ctx,env) {
    this.ctx=ctx;this.env=env;this.record=null;
    ctx.blockConcurrencyWhile(async()=>{this.record=await ctx.storage.get('room')||null;});
    if(typeof WebSocketRequestResponsePair!=='undefined')ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping','pong'));
  }
  async save(){await this.ctx.storage.put('room',this.record);}
  // getWebSockets takes one tag, not an array (Cloudflare Hibernation API).
  peers(role){return this.ctx.getWebSockets(role);}
  send(role,message){for(const socket of this.peers(role))safeSend(socket,message);}
  async role(request) {
    if(!this.record||this.record.closed||Date.now()>=this.record.expiresAt)return null;
    const secret=bearer(request);if(!/^[A-Za-z0-9_-]{32}$/.test(secret))return null;
    const hash=await digest(secret);
    if(hash===this.record.hostHash)return 'host';if(hash===this.record.guestHash)return 'guest';return null;
  }
  async fetch(request) {
    const path=new URL(request.url).pathname;
    if(path==='/quota'){
      const day=new Date().toISOString().slice(0,10);
      const cap=boundedInt(this.env.MAX_ROOMS_PER_DAY,20,1,500);
      const value=await this.ctx.storage.transaction(async tx=>{
        const q=await tx.get('quota');const next={day,count:q?.day===day?q.count+1:1};
        if(next.count>cap)return false;await tx.put('quota',next);return true;
      });
      return value?json({ok:true}):fail('DAILY_LIMIT','Daily session limit reached. Try again tomorrow.',429);
    }
    if(path==='/init'){
      if(this.record)return fail('EXISTS','Session exists.',409);
      this.record={...await request.json(),closed:false,approved:'',guestId:'',guestName:'',iceByRole:{}};
      await this.save();await this.ctx.storage.setAlarm(this.record.expiresAt);return json({ok:true});
    }
    const role=await this.role(request);
    if(!role)return fail('SESSION_ENDED','This invitation is invalid or has expired.',401);
    if(path==='/end'&&role==='host'&&request.method==='POST'){await this.finish();return json({ok:true});}
    if(path==='/ice'&&request.method==='GET'){
      if(role==='guest'&&(!this.record.approved||request.headers.get('x-showme-viewer')!==this.record.approved))
        return fail('APPROVAL_REQUIRED','The camera owner must approve this connection.',403);
      return this.ice(role);
    }
    if(path!==`/ws/${role}`||request.headers.get('upgrade')?.toLowerCase()!=='websocket')return fail('NOT_FOUND','Unknown session endpoint.',404);
    if(role==='guest'&&this.peers('guest').length)return fail('HELPER_BUSY','Another helper is already connected.',409);
    if(role==='host')for(const existing of this.peers('host'))existing.close(4001,'Owner reconnected');
    const pair=new WebSocketPair(),client=pair[0],server=pair[1];
    const attachment={role,id:token(12),viewerId:'',windowAt:Date.now(),count:0};
    this.ctx.acceptWebSocket(server,[role]);server.serializeAttachment(attachment);
    if(role==='host'){
      this.record.hostConnection=attachment.id;await this.save();
      this.send('guest',{type:'owner-online'});
      if(this.record.guestId&&!this.record.approved)safeSend(server,{type:'join-request',viewerId:this.record.guestId,name:this.record.guestName});
    }
    safeSend(server,{type:'hello',role,expiresAt:this.record.expiresAt,ownerName:this.record.ownerName,protocol:3});
    const protocols=(request.headers.get('sec-websocket-protocol')||'').split(',').map(v=>v.trim());
    return new Response(null,{status:101,webSocket:client,headers:protocols.includes(SOCKET_PROTOCOL)?{'Sec-WebSocket-Protocol':SOCKET_PROTOCOL}:{}});
  }
  async ice(role) {
    const cached=this.record.iceByRole?.[role];
    if(cached&&Date.now()<cached.expiresAt-60_000)return json({ok:true,iceServers:cached.servers,expiresAt:cached.expiresAt});
    if(!this.env.TURN_KEY_ID||!this.env.TURN_API_TOKEN)
      return fail('RELAY_NOT_CONFIGURED','The ShowMe service needs its TURN key configured before Internet calls.',503);
    // Credentials last beyond the maximum room lifetime, but never live inside the APK/source.
    const ttl=Math.min(MAX_SESSION_SECONDS+300,Math.max(600,Math.ceil((this.record.expiresAt-Date.now())/1000)+120));
    const response=await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(this.env.TURN_KEY_ID)}/credentials/generate-ice-servers`,{
      method:'POST',headers:{Authorization:`Bearer ${this.env.TURN_API_TOKEN}`,'Content-Type':'application/json'},body:JSON.stringify({ttl})});
    if(!response.ok)return fail('RELAY_UNAVAILABLE','Relay credentials could not be issued. Check the service configuration.',503);
    const body=await response.json(),ice=normalizeIce(body.iceServers);
    if(!ice.some(entry=>entry.urls.some(u=>/^turns?:/.test(u))))return fail('RELAY_UNAVAILABLE','TURN response was invalid.',503);
    const expiresAt=Date.now()+ttl*1000;this.record.iceByRole[role]={servers:ice,expiresAt};await this.save();
    return json({ok:true,iceServers:ice,expiresAt});
  }
  async webSocketMessage(socket,data) {
    if(data==='ping'){safeSend(socket,'pong');return;}
    if(!this.record||this.record.closed||Date.now()>=this.record.expiresAt){socket.close(4004,'Session ended');return;}
    const attachment=socket.deserializeAttachment();
    if(!attachment||typeof data!=='string'||new TextEncoder().encode(data).length>MAX_SIGNAL_BYTES){socket.close(1009,'Invalid signaling payload');return;}
    const now=Date.now();if(now-attachment.windowAt>10_000){attachment.windowAt=now;attachment.count=0;}
    if(++attachment.count>60){socket.close(1008,'Rate limit');return;}socket.serializeAttachment(attachment);
    if(attachment.role==='host'&&attachment.id!==this.record.hostConnection){socket.close(4001,'Stale connection');return;}
    let message;try{message=JSON.parse(data);}catch{safeSend(socket,{type:'error',message:'Invalid message'});return;}
    if(message.type==='join'&&attachment.role==='guest'){
      if(!ID_RE.test(message.viewerId||'')){socket.close(1008,'Invalid viewer ID');return;}
      attachment.viewerId=message.viewerId;socket.serializeAttachment(attachment);
      this.record.guestId=message.viewerId;this.record.guestName=cleanName(message.name);await this.save();
      if(this.record.approved===message.viewerId){safeSend(socket,{type:'approved',ownerName:this.record.ownerName});}
      else{this.send('host',{type:'join-request',viewerId:message.viewerId,name:this.record.guestName});
        safeSend(socket,{type:'waiting',ownerOnline:this.peers('host').length>0});}
      return;
    }
    if(message.type==='approve'&&attachment.role==='host'&&message.viewerId===this.record.guestId){
      this.record.approved=message.viewerId;await this.save();this.send('guest',{type:'approved',ownerName:this.record.ownerName});return;
    }
    if(message.type==='deny'&&attachment.role==='host'&&message.viewerId===this.record.guestId){
      this.send('guest',{type:'denied'});for(const peer of this.peers('guest'))peer.close(4003,'Invitation declined');
      this.record.approved='';await this.save();return;
    }
    if(message.type==='end'&&attachment.role==='host'){await this.finish();return;}
    const approved=!!this.record.approved&&(attachment.role==='host'||this.record.approved===attachment.viewerId);
    if(approvedSignal(message,attachment.role,approved)){
      // Forward only fields from the signaling protocol; never accept camera frames or arbitrary RPC.
      const outgoing=message.type==='offer'?{type:'offer',id:message.id,sdp:message.sdp,viewerId:attachment.viewerId}:
        {type:'answer',id:message.id,sdp:message.sdp,video:message.video,ok:message.ok!==false,message:message.message};
      this.send(attachment.role==='host'?'guest':'host',outgoing);return;
    }
    safeSend(socket,{type:'error',id:ID_RE.test(message.id||'')?message.id:undefined,message:'Message not allowed.'});
  }
  async webSocketClose(socket,code,reason) {
    const a=socket.deserializeAttachment();if(!this.record||!a)return;
    if(a.role==='host'&&a.id===this.record.hostConnection)this.send('guest',{type:'owner-offline'});
    if(a.role==='guest'&&a.viewerId===this.record.guestId){
      this.send('host',{type:'helper-left',viewerId:a.viewerId});
      // Retain approval for reconnect with the same random viewer ID, until expiry.
    }
    try{socket.close(code,reason);}catch{}
  }
  async webSocketError(socket){await this.webSocketClose(socket,1011,'Connection interrupted');}
  async finish(){
    if(!this.record)return;
    this.record.closed=true;this.record.iceByRole={};await this.save();
    for(const socket of this.ctx.getWebSockets()){safeSend(socket,{type:'ended'});socket.close(4004,'Session ended');}
    await this.ctx.storage.deleteAll();this.record=null;
  }
  async alarm(){await this.finish();}
}
