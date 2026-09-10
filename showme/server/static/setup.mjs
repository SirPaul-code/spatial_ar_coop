const status=document.getElementById('status');
const key=new URLSearchParams(location.hash.slice(1)).get('key');
const button=document.getElementById('activate');
const valid=/^[A-Za-z0-9_-]{32,128}$/.test(key??'');
if(!valid){button.removeAttribute('href');status.textContent='This page needs the complete activation link printed by the deployment script.';}
else{
  button.href=`showme://connect?server=${encodeURIComponent(location.origin)}#key=${encodeURIComponent(key)}`;
  fetch('/api/health',{cache:'no-store'}).then(r=>r.json()).then(data=>{
    status.textContent=data.configured&&data.relayConfigured?'Your service is ready.':'Service setup is incomplete. Run npm run configure from the deployment folder.';
  }).catch(()=>{status.textContent='Could not reach your service.';});
}
document.getElementById('copy').addEventListener('click',async()=>{
  if(!valid)return;
  try{await navigator.clipboard.writeText(location.href);status.textContent='Activation copied. Paste it only into your own ShowMe camera app.';}
  catch{status.textContent='Copy the full address from the browser address bar.';}
});
