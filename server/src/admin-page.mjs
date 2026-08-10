export function adminPage() {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Spatial AR Coop</title>
<style>
:root{color-scheme:dark;--bg:#07110e;--panel:#101f1b;--line:#264239;--accent:#75e7b0;--warn:#ffb85c;--muted:#a7beb4}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:#eef7f2;font:14px ui-monospace,SFMono-Regular,Consolas,monospace}
header{padding:20px 24px;border-bottom:1px solid var(--line);display:flex;gap:16px;align-items:center;flex-wrap:wrap}
h1{font:700 24px system-ui;margin:0;color:var(--accent)}input,button{background:#091612;color:#fff;border:1px solid var(--line);border-radius:7px;padding:9px 11px}
button{cursor:pointer}main{padding:18px;display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px;min-height:120px}
h2{font:700 13px system-ui;letter-spacing:.12em;color:var(--accent);margin:0 0 10px}.metric{display:inline-block;margin:4px 16px 4px 0}.metric b{font-size:24px;display:block}.muted{color:var(--muted)}.warn{color:var(--warn)}
pre{white-space:pre-wrap;overflow:auto;max-height:420px;background:#050b09;padding:10px;border-radius:8px}.row{border-top:1px solid var(--line);padding:8px 0}.pill{display:inline-block;border:1px solid var(--line);border-radius:99px;padding:2px 7px;margin-right:5px}
</style>
</head>
<body>
<header><h1>SPATIAL AR COOP</h1><span id="state" class="warn">connecting</span><input id="token" type="password" placeholder="admin/API token"><button id="save">Save token</button><button id="refresh">Refresh</button></header>
<main>
<section class="card"><h2>SERVER</h2><div id="metrics"></div></section>
<section class="card"><h2>MAPS</h2><div id="maps" class="muted">loading</div></section>
<section class="card"><h2>LIVE LOG</h2><pre id="logs"></pre></section>
<section class="card"><h2>PROTOCOL</h2><div class="muted">Phones publish compact object tracks, poses, map metadata, and sparse point-cloud chunks. Camera video stays on-device.</div><div style="margin-top:12px"><span class="pill">MAP</span><span class="pill">SENSOR</span><span class="pill">VIEWER</span></div></section>
</main>
<script>
const tokenEl=document.getElementById('token');tokenEl.value=localStorage.spatialToken||'';
const headers=()=>tokenEl.value?{'Authorization':'Bearer '+tokenEl.value}:{};
async function get(path){const r=await fetch(path,{headers:headers()});if(!r.ok)throw new Error(await r.text());return r.json()}
function esc(v){return String(v).replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]))}
async function refresh(){try{const [metrics,maps,logs]=await Promise.all([get('/api/v1/metrics'),get('/api/v1/maps'),get('/api/v1/logs?limit=100')]);
 document.getElementById('state').textContent='online';document.getElementById('state').className='';
 document.getElementById('metrics').innerHTML=Object.entries(metrics).map(([k,v])=>'<span class="metric"><b>'+esc(v)+'</b>'+esc(k)+'</span>').join('');
 document.getElementById('maps').innerHTML=maps.maps.length?maps.maps.map(m=>'<div class="row"><b>'+esc(m.name)+'</b><br><span class="muted">'+esc(m.id)+' · '+esc(m.status)+' · '+m.anchors.length+' anchors · '+m.scan.pointCount+' points</span></div>').join(''):'No maps';
 document.getElementById('logs').textContent=logs.entries.map(e=>e.ts+' '+e.level.toUpperCase()+' '+e.message+' '+JSON.stringify(e)).join('\n');
 }catch(e){document.getElementById('state').textContent='error: '+e.message;document.getElementById('state').className='warn'}}
document.getElementById('save').onclick=()=>{localStorage.spatialToken=tokenEl.value;refresh()};document.getElementById('refresh').onclick=refresh;refresh();setInterval(refresh,3000);
</script></body></html>`;
}
