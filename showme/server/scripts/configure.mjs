import {createInterface} from 'node:readline/promises';
import {spawn} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const cli=path.join(root,'node_modules/wrangler/bin/wrangler.js');
const input=createInterface({input:process.stdin,output:process.stdout});
async function secret(name,value){
  await new Promise((resolve,reject)=>{
    const child=spawn(process.execPath,[cli,'secret','put',name],{cwd:root,stdio:['pipe','inherit','inherit']});
    child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(new Error(`Could not set ${name}`)));
    child.stdin.end(value+'\n');
  });
}
try{
  console.log('ShowMe server setup. This is operator-only; app users never see these credentials.');
  const origin=(await input.question('Paste the deployed HTTPS Worker URL: ')).trim().replace(/\/$/,'');
  const url=new URL(origin);if(url.protocol!=='https:'||url.pathname!=='/'||url.search||url.hash||url.username)throw new Error('Use the HTTPS origin, without a path.');
  const keyId=(await input.question('Cloudflare Realtime TURN Key ID: ')).trim();
  console.log('The next entry is a secret. Do not record or share this terminal.');
  const apiToken=(await input.question('TURN Key API token: ')).trim();
  if(!keyId||apiToken.length<16)throw new Error('TURN key/token are missing. Use the credentials from Realtime > TURN.');
  const signingKey=randomBytes(32).toString('base64url');
  await secret('TURN_KEY_ID',keyId);await secret('TURN_API_TOKEN',apiToken);await secret('ROOM_CREATE_KEY',signingKey);
  let info=null,lastStatus=0;
  for(let attempt=0;attempt<8;attempt++){
    try{
      const response=await fetch(`${origin}/api/health`,{cache:'no-store',signal:AbortSignal.timeout(10000)});lastStatus=response.status;info=await response.json();
      if(response.ok&&info.configured&&info.relayConfigured)break;
    }catch{}
    await new Promise(resolve=>setTimeout(resolve,750+attempt*500));
  }
  if(!info?.configured||!info?.relayConfigured)throw new Error(`Secrets were uploaded, but service health did not confirm them yet (HTTP ${lastStatus||'unreachable'}).`);
  console.log('\nService configured. Android users do not need an activation link or Cloudflare credentials.');
  console.log('The app registers itself automatically on first Internet call.');
}catch(error){console.error(`Setup failed: ${error.message}`);process.exitCode=1;}finally{input.close();}
