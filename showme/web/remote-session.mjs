import {requestId} from './control-rpc.mjs';
/** Only SDP/presence use the Worker. Frozen images and input stay on the media connection. */
export class RemoteSession {
  constructor({roomId,token,viewerId,getControl,onStatus=()=>{},onEnded=()=>{}}){
    Object.assign(this,{roomId,token,viewerId,getControl,onStatus,onEnded});
    this.socket=null;this.approved=false;this.closed=false;this.pending=new Map();this.retry=0;this.state={};
  }
  async request(path,body={},options={}){
    if(path==='/api/join')return this.join(body.name);
    if(path==='/api/leave'){this.close();return {ok:true};}
    if(path==='/api/ice'){
      const response=await fetch(`/api/rooms/${this.roomId}/ice`,{headers:{Authorization:`Bearer ${this.token}`,'X-ShowMe-Viewer':this.viewerId},cache:'no-store'});
      const data=await response.json();if(!response.ok||data.ok===false){const error=new Error(data.message||'Relay is unavailable');error.code=data.code;throw error;}return data;
    }
    if(path==='/api/call')return this.offer(body.sdp);
    if(path==='/api/call-stop')return {ok:true};
    const control=this.getControl();
    if(path==='/api/state'&&!control?.ready)return {...this.state,ok:true,active:true,internet:true,tracking:false,videoState:'CONNECTING'};
    if(!control?.ready)throw new Error('Wait for the call to connect before drawing.');
    const result=await control.request(path,body??{});
    if(path==='/api/state')this.state=result;
    return result;
  }
  join(name){
    this.name=name||'Helper';this.closed=false;
    if(this.approved&&this.socket?.readyState===WebSocket.OPEN)return Promise.resolve(this.initialState());
    return new Promise((resolve,reject)=>{
      clearTimeout(this.joinTimer);this.joinResolve=resolve;this.joinReject=reject;
      this.joinTimer=setTimeout(()=>{reject(new Error('The owner has not approved this call yet. Try again.'));this.close();},90000);
      this.connect();
    });
  }
  initialState(){return {ok:true,active:true,internet:true,secure:true,tracking:false,annotations:0,hostName:this.ownerName||'Camera'};}
  connect(){
    if(this.closed)return;
    const scheme=location.protocol==='https:'?'wss:':'ws:';
    const socket=new WebSocket(`${scheme}//${location.host}/api/rooms/${this.roomId}/ws/guest`,['showme.v1',`cap.${this.token}`]);
    this.socket=socket;
    socket.onopen=()=>{if(this.socket!==socket)return;this.retry=0;socket.send(JSON.stringify({type:'join',viewerId:this.viewerId,name:this.name}));
      clearInterval(this.ping);this.ping=setInterval(()=>{if(socket.readyState===WebSocket.OPEN)socket.send('ping');},25000);};
    socket.onmessage=event=>{
      if(socket!==this.socket||event.data==='pong')return;
      let message;try{message=JSON.parse(event.data);}catch{return;}
      if(message.type==='hello'){this.ownerName=message.ownerName;return;}
      if(message.type==='waiting'){this.onStatus(message.ownerOnline?'Waiting for the owner to let you in':'Waiting for the camera owner');return;}
      if(message.type==='approved'){
        this.approved=true;clearTimeout(this.joinTimer);this.ownerName=message.ownerName||this.ownerName;
        this.joinResolve?.(this.initialState());this.joinResolve=null;this.joinReject=null;this.onStatus('Connecting your call');return;
      }
      if(message.type==='denied'){this.joinReject?.(new Error('The camera owner declined this invitation.'));this.close();return;}
      if(message.type==='ended'){this.joinReject?.(new Error('The session has ended.'));this.close();this.onEnded('The camera owner ended this call.');return;}
      if(message.type==='owner-offline')this.onStatus('Reconnecting to the camera owner');
      if(message.type==='answer'||message.type==='error'){
        const job=this.pending.get(message.id);if(!job)return;this.pending.delete(message.id);clearTimeout(job.timer);
        if(message.ok===false||message.type==='error')job.reject(new Error(message.message||'Call could not connect'));
        else job.resolve(message);
      }
    };
    socket.onclose=event=>{
      clearInterval(this.ping);if(socket!==this.socket||this.closed)return;
      if([4003,4004].includes(event.code)){this.joinReject?.(new Error('This invitation has ended.'));this.close();this.onEnded('This invitation has ended.');return;}
      if(++this.retry>5){this.joinReject?.(new Error('Cannot reach this call. Check the invitation and try again.'));this.close();return;}
      this.onStatus('Reconnecting the invitation');this.reconnect=setTimeout(()=>this.connect(),Math.min(15000,1000*2**this.retry));
    };
    socket.onerror=()=>{};
  }
  offer(sdp){
    if(!this.approved||this.socket?.readyState!==WebSocket.OPEN)return Promise.reject(new Error('Waiting for owner approval.'));
    const id=requestId();return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('The camera did not answer the call.'));},20000);
      this.pending.set(id,{resolve,reject,timer});this.socket.send(JSON.stringify({type:'offer',id,sdp}));
    });
  }
  close(){this.closed=true;this.approved=false;clearInterval(this.ping);clearTimeout(this.reconnect);clearTimeout(this.joinTimer);
    this.socket?.close(1000,'Left call');this.socket=null;
    for(const job of this.pending.values()){clearTimeout(job.timer);job.reject(new Error('Call ended'));}this.pending.clear();}
}
