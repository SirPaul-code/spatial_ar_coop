import {mkdir,copyFile,access} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const web=path.resolve(root,'../web');
const output=path.join(root,'public');await mkdir(output,{recursive:true});
for(const file of ['index.html','style.css','app.js','geometry.mjs','live-video.mjs','remote-session.mjs','control-rpc.mjs']){
  try{await access(path.join(web,file));await copyFile(path.join(web,file),path.join(output,file));}
  catch(error){throw new Error(`Missing ../web/${file}. Keep the web and server folders together.`,{cause:error});}
}
await copyFile(path.join(root,'static','welcome.html'),path.join(output,'welcome.html'));
await copyFile(path.join(root,'static','_headers'),path.join(output,'_headers'));
console.log('Browser assets prepared. Helper SPA routing and security headers are included; no activation page or camera files are uploaded.');
