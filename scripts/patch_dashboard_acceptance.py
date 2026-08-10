from pathlib import Path
import re

p = Path('server/src/admin-page.mjs')
text = p.read_text()

text, n = re.subn(
    r'(\.inspector\{[^\n]*?)grid-template-columns:1fr 1fr;',
    r'\1grid-template-columns:1fr 1fr 1fr;',
    text,
    count=1,
)
assert n == 1, 'inspector grid not found'
text = text.replace(
    '.tr.head{color:var(--muted);font-size:10px}',
    '.tr.head{color:var(--muted);font-size:10px}.tr.five{grid-template-columns:minmax(74px,1.2fr) .7fr .8fr .8fr .8fr}',
    1,
)

old = '<div class="inspector"><section><h2>Participants</h2><div id="clients" class="table"></div></section><section><h2>Tracks</h2><div id="tracks" class="table"></div></section></div>'
new = '<div class="inspector"><section><h2>Anchors</h2><div id="anchors" class="table"></div></section><section><h2>Participants</h2><div id="clients" class="table"></div></section><section><h2>Tracks</h2><div id="tracks" class="table"></div></section></div>'
assert text.count(old) == 1, 'inspector html not found'
text = text.replace(old, new, 1)

needle = "async function loadGeometry(){"
assert text.count(needle) == 1
render_anchors = r'''function renderAnchors(){const anchors=state.map&&state.map.anchors||[];$('anchors').innerHTML='<div class="tr head"><span>Anchor</span><span>Status</span><span>Quality</span><span>Error</span></div>'+anchors.map(a=>{const bad=a.status==='FAILED'||a.status==='NEEDS_RESCAN';const err=a.lastError||'—';return '<div class="tr"><span class="mono">'+esc(short(a.id))+'</span><span class="'+(bad?'amber':'blue')+'">'+esc(a.status||'UNKNOWN')+'</span><span>'+esc(a.featureQuality||'UNKNOWN')+'</span><span class="'+(bad?'amber':'dim')+'" title="'+esc(err)+'">'+esc(short(err))+'</span></div>'}).join('');if(!anchors.length)$('anchors').innerHTML+='<div class="dim" style="padding:9px 0">No anchors in this map.</div>'}
'''
text = text.replace(needle, render_anchors + needle, 1)

# Keep the anchor table synchronized whenever map metadata is rendered.
old_end = "<div class=\"fact\"><span>Anchor issues</span><b class=\"'+(bad?'amber':'')+'\">'+bad+'</b></div></div>'}"
if old_end in text:
    text = text.replace(old_end, old_end[:-1] + ";renderAnchors()}", 1)
else:
    # Safer regex fallback against the compact one-line function.
    text, n = re.subn(r"(function renderMapState\(\)\{.*?)(\}\nasync function loadGeometry)", lambda m: m.group(1).rstrip('}') + ";renderAnchors()}\nasync function loadGeometry", text, count=1, flags=re.S)
    assert n == 1, 'renderMapState hook not found'

old_live = re.search(r"function renderLive\(\)\{.*?\}\nconst canvas=", text, flags=re.S)
assert old_live, 'renderLive not found'
new_live = r'''function renderLive(){const now=state.live.serverTimeMs||Date.now();const clients=state.live.clients||[];const tracks=state.live.tracks||[];const newestBySource=new Map();for(const t of tracks){const ts=t.serverReceivedAtMs||0;if(ts>(newestBySource.get(t.sourceId)||0))newestBySource.set(t.sourceId,ts)}$('clients').innerHTML='<div class="tr five head"><span>Device</span><span>Role</span><span>State</span><span>Last pose</span><span>Last track</span></div>'+clients.map(c=>{const poseAge=c.pose?now-c.pose.serverReceivedAtMs:Infinity;const trackTs=newestBySource.get(c.clientId)||0;const trackAge=trackTs?Math.max(0,now-trackTs):Infinity;const stale=poseAge>5000;const s=c.status&&c.status.state?c.status.state:(c.pose&&c.pose.trackingState?c.pose.trackingState:'connected');return '<div class="tr five"><span class="mono">'+esc(short(c.clientId))+'</span><span>'+esc(c.role)+'</span><span class="'+(stale?'dim':'blue')+'">'+esc(s)+'</span><span class="'+(stale?'dim':'')+'">'+age(poseAge)+'</span><span class="'+(trackAge>3000?'dim':'red')+'">'+age(trackAge)+'</span></div>'}).join('');if(!clients.length)$('clients').innerHTML+='<div class="dim" style="padding:9px 0">No live participants.</div>';$('tracks').innerHTML='<div class="tr head"><span>Object</span><span>Source</span><span>Confidence</span><span>Age</span></div>'+tracks.map(t=>{const a=Math.max(0,now-(t.serverReceivedAtMs||now));const stale=a>2500;return '<div class="tr"><span class="'+(stale?'dim':'red')+'">'+esc(t.label)+'</span><span class="mono">'+esc(short(t.sourceId))+'</span><span>'+Math.round((t.confidence||0)*100)+'%</span><span class="'+(stale?'dim':'')+'">'+age(a)+'</span></div>'}).join('');if(!tracks.length)$('tracks').innerHTML+='<div class="dim" style="padding:9px 0">No live tracks.</div>'}
const canvas='''
text = text[:old_live.start()] + new_live + text[old_live.end():]

# Refresh anchor diagnostics when periodic map metadata refreshes.
text = text.replace("state.map=updated;renderMapState()", "state.map=updated;renderMapState();renderAnchors()", 1)

p.write_text(text)
print('dashboard acceptance patch applied')
