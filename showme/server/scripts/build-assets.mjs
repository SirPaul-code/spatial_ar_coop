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
for(const file of ['setup.html','setup.mjs','welcome.html'])await copyFile(path.join(root,'static',file),path.join(output,file));
console.log('Browser and activation assets prepared. No camera files are uploaded.');
