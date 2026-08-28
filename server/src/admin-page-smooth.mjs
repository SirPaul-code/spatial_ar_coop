import { adminPage as baseAdminPage } from './admin-page.mjs';

/**
 * Performance wrapper for the classic World Debugger.
 *
 * The original page remains the source of all controls/tables and API behavior. This injected
 * layer increases live-state sampling, renders moving clients/tracks on requestAnimationFrame,
 * predicts short track motion from velocity, and caches heavy static geometry on a background
 * canvas. It is render-only and does not alter persisted or relayed track data.
 */
export function adminPage() {
  const page = baseAdminPage();
  const patch = String.raw`<script>
(() => {
  const scene = document.getElementById('scene');
  if (!scene || typeof state === 'undefined') return;

  const staticCanvas = document.createElement('canvas');
  staticCanvas.id = 'sceneStaticClassic';
  staticCanvas.setAttribute('aria-hidden', 'true');
  staticCanvas.style.position = 'absolute';
  staticCanvas.style.inset = '0';
  staticCanvas.style.width = '100%';
  staticCanvas.style.height = '100%';
  staticCanvas.style.pointerEvents = 'none';
  staticCanvas.style.zIndex = '0';
  scene.style.zIndex = '1';
  scene.parentNode.insertBefore(staticCanvas, scene);
  for (const el of scene.parentNode.querySelectorAll('.toolbar,.hud,.legend')) el.style.zIndex = '4';

  const staticCtx = staticCanvas.getContext('2d', { alpha: false });
  const dynamicCtx = scene.getContext('2d', { alpha: true });
  const samples = [];
  let visualLive = null;
  let visualAt = performance.now();
  let projectionCache = null;
  let staticKey = '';
  let staticAt = -Infinity;
  let hudAt = -Infinity;
  let tableAt = -Infinity;
  let fpsAt = performance.now();
  let fpsFrames = 0;
  let fps = 0;
  let liveBusy = false;

  const LIVE_POLL_MS = 100;
  const MAX_PREDICTION_MS = 180;
  const VISUAL_TAU_SECONDS = 0.055;
  const STATIC_INTERVAL_MOVING_MS = 32;
  const HUD_INTERVAL_MS = 180;
  const TABLE_INTERVAL_MS = 500;
  const CLOUD_TARGET_POINTS = 12000;

  function mix(a, b, t) { return a + (b - a) * t; }
  function mix3(a, b, t) {
    a = v3(a); b = v3(b);
    return [mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t)];
  }
  function mixAngle(a, b, t) {
    let delta = (b - a + Math.PI) % (Math.PI * 2) - Math.PI;
    if (delta < -Math.PI) delta += Math.PI * 2;
    return a + delta * t;
  }
  function nlerpQuat(a, b, t) {
    if (!Array.isArray(a) || a.length < 4) return b;
    if (!Array.isArray(b) || b.length < 4) return a;
    let bx = +b[0] || 0, by = +b[1] || 0, bz = +b[2] || 0, bw = +b[3] || 1;
    const dotQ = (+a[0] || 0) * bx + (+a[1] || 0) * by + (+a[2] || 0) * bz + (+a[3] || 1) * bw;
    if (dotQ < 0) { bx = -bx; by = -by; bz = -bz; bw = -bw; }
    const q = [
      mix(+a[0] || 0, bx, t), mix(+a[1] || 0, by, t),
      mix(+a[2] || 0, bz, t), mix(+a[3] || 1, bw, t)
    ];
    const length = Math.hypot(q[0], q[1], q[2], q[3]) || 1;
    return q.map(value => value / length);
  }

  function keyForTrack(track) {
    return String(track?.key || ((track?.sourceId || '') + ':' + (track?.id || '')));
  }

  function predictedTarget(now) {
    if (!samples.length) return state.live;
    const latest = samples[samples.length - 1];
    const previous = samples.length > 1 ? samples[samples.length - 2] : null;
    const ageMs = Math.max(0, Math.min(MAX_PREDICTION_MS, now - latest.at));
    const dt = ageMs / 1000;
    const prevClients = new Map((previous?.data?.clients || []).map(client => [client.clientId, client]));
    const sampleDt = previous ? Math.max(0.02, (latest.at - previous.at) / 1000) : 0;

    const clients = (latest.data.clients || []).map(client => {
      if (!client.pose?.position) return client;
      const old = prevClients.get(client.clientId);
      if (!old?.pose?.position || !sampleDt) return client;
      const a = v3(old.pose.position), b = v3(client.pose.position);
      const velocity = [(b[0] - a[0]) / sampleDt, (b[1] - a[1]) / sampleDt, (b[2] - a[2]) / sampleDt];
      const speed = Math.hypot(velocity[0], velocity[1], velocity[2]);
      const scale = speed > 12 ? 12 / speed : 1;
      const predicted = [
        b[0] + velocity[0] * scale * dt,
        b[1] + velocity[1] * scale * dt,
        b[2] + velocity[2] * scale * dt
      ];
      return { ...client, pose: { ...client.pose, position: predicted } };
    });

    const tracks = (latest.data.tracks || []).map(track => {
      const p = v3(track.position);
      const velocity = v3(track.velocity);
      const speed = Math.hypot(velocity[0], velocity[1], velocity[2]);
      const limit = track.label === 'car' ? 30 : track.label === 'person' ? 8 : 15;
      const scale = speed > limit ? limit / speed : 1;
      return {
        ...track,
        position: [
          p[0] + velocity[0] * scale * dt,
          p[1] + velocity[1] * scale * dt,
          p[2] + velocity[2] * scale * dt
        ]
      };
    });
    return { ...latest.data, clients, tracks, serverTimeMs: Number(latest.data.serverTimeMs || Date.now()) + ageMs };
  }

  function mixLive(a, b, t) {
    if (!a) return b;
    if (!b) return a;
    const oldClients = new Map((a.clients || []).map(client => [client.clientId, client]));
    const clients = (b.clients || []).map(client => {
      const old = oldClients.get(client.clientId);
      if (!old?.pose || !client.pose) return client;
      return {
        ...client,
        pose: {
          ...client.pose,
          position: mix3(old.pose.position, client.pose.position, t),
          rotation: nlerpQuat(old.pose.rotation, client.pose.rotation, t)
        }
      };
    });
    const oldTracks = new Map((a.tracks || []).map(track => [keyForTrack(track), track]));
    const tracks = (b.tracks || []).map(track => {
      const old = oldTracks.get(keyForTrack(track));
      if (!old) return track;
      return {
        ...track,
        position: mix3(old.position, track.position, t),
        velocity: mix3(old.velocity, track.velocity, t),
        extentMeters: mix3(old.extentMeters, track.extentMeters, t),
        confidence: mix(Number(old.confidence || 0), Number(track.confidence || 0), t),
        uncertaintyMeters: mix(Number(old.uncertaintyMeters || 0), Number(track.uncertaintyMeters || 0), t),
        yawRadians: mixAngle(Number(old.yawRadians || 0), Number(track.yawRadians || 0), t)
      };
    });
    return { ...b, clients, tracks };
  }

  function renderLive(now) {
    const target = predictedTarget(now);
    if (!visualLive) { visualLive = target; visualAt = now; return target; }
    const dt = Math.max(0.001, Math.min(0.05, (now - visualAt) / 1000));
    visualAt = now;
    const alpha = 1 - Math.exp(-dt / VISUAL_TAU_SECONDS);
    visualLive = mixLive(visualLive, target, alpha);
    return visualLive;
  }

  const originalRefreshLive = refreshLive;
  refreshLive = async function smoothRefreshLive() {
    if (liveBusy || !state.authenticated || !state.map || state.paused) return;
    liveBusy = true;
    try {
      const data = await get('/api/v1/maps/' + encodeURIComponent(state.map.id) + '/live-state');
      state.live = data;
      samples.push({ at: performance.now(), data });
      if (samples.length > 8) samples.splice(0, samples.length - 8);
      const now = data.serverTimeMs || Date.now();
      for (const client of data.clients || []) {
        if (client.pose?.position) appendTrail(state.clientTrails, client.clientId, v3(client.pose.position), client.pose.serverReceivedAtMs || now);
      }
      for (const track of data.tracks || []) {
        if (track.position) appendTrail(state.trackTrails, keyForTrack(track), v3(track.position), track.serverReceivedAtMs || now);
      }
      if (performance.now() - tableAt >= TABLE_INTERVAL_MS) {
        tableAt = performance.now();
        renderTables();
      }
      $('connection').textContent = 'Live · ' + (data.clients || []).length + ' clients · ' + (data.tracks || []).length + ' tracks';
      $('connection').className = 'chip grow ok';
    } catch (error) {
      $('connection').textContent = 'Live API: ' + error.message;
      $('connection').className = 'chip grow bad';
    } finally {
      liveBusy = false;
    }
  };

  function beginProjection(w, h) {
    const b = bounds();
    const cx = (b.min[0] + b.max[0]) / 2;
    const cy = (b.min[1] + b.max[1]) / 2;
    const cz = (b.min[2] + b.max[2]) / 2;
    const span = Math.max(1, b.max[0] - b.min[0], b.max[2] - b.min[2], b.max[1] - b.min[1]);
    const scale = Math.min(w, h) * 0.78 / span * state.zoom;
    projectionCache = {
      w, h, cx, cy, cz, span, scale, mode: state.mode,
      ca: Math.cos(state.yaw), sa: Math.sin(state.yaw),
      cp: Math.cos(state.pitch), sp: Math.sin(state.pitch)
    };
  }

  project = function cachedProject(value, w = scene.clientWidth, h = scene.clientHeight) {
    if (!projectionCache || projectionCache.w !== w || projectionCache.h !== h || projectionCache.mode !== state.mode) beginProjection(w, h);
    const c = projectionCache;
    const p = v3(value);
    const x = p[0] - c.cx, y = p[1] - c.cy, z = p[2] - c.cz;
    if (c.mode === '2d') return [w / 2 + x * c.scale, h / 2 + z * c.scale, 0];
    const rx = x * c.ca - z * c.sa;
    const rz = x * c.sa + z * c.ca;
    const ry = y * c.cp - rz * c.sp;
    const depth = y * c.sp + rz * c.cp;
    return [w / 2 + rx * c.scale, h / 2 - ry * c.scale, depth];
  };

  function staticSignature(w, h) {
    return [w.toFixed(1), h.toFixed(1), state.mode,
      Number(state.yaw).toFixed(4), Number(state.pitch).toFixed(4), Number(state.zoom).toFixed(4),
      state.layers.points ? 1 : 0, state.layers.terrain ? 1 : 0, state.layers.anchors ? 1 : 0, state.layers.labels ? 1 : 0,
      state.cloud?.points?.length || 0, state.terrain?.length || 0, state.map?.anchors?.length || 0].join('|');
  }

  function staticLine(a, b, color, width = 1, alpha = 1) {
    const A = project(a), B = project(b);
    staticCtx.globalAlpha = alpha;
    staticCtx.strokeStyle = color;
    staticCtx.lineWidth = width;
    staticCtx.beginPath();
    staticCtx.moveTo(A[0], A[1]);
    staticCtx.lineTo(B[0], B[1]);
    staticCtx.stroke();
    staticCtx.globalAlpha = 1;
  }

  function staticDot(p, radius, color, stroke) {
    const q = project(p);
    staticCtx.beginPath();
    staticCtx.arc(q[0], q[1], radius, 0, Math.PI * 2);
    staticCtx.fillStyle = color;
    staticCtx.fill();
    if (stroke) { staticCtx.strokeStyle = stroke; staticCtx.stroke(); }
    return q;
  }

  function staticLabel(p, value, color) {
    if (!state.layers.labels) return;
    const q = project(p);
    staticCtx.font = '11px ui-monospace,monospace';
    const width = staticCtx.measureText(value).width + 10;
    staticCtx.fillStyle = 'rgba(10,11,14,.84)';
    staticCtx.fillRect(q[0] + 7, q[1] - 17, width, 18);
    staticCtx.fillStyle = color;
    staticCtx.fillText(value, q[0] + 12, q[1] - 5);
  }

  function drawStatic(now, w, h, dpr, force) {
    const signature = staticSignature(w, h);
    if (!force && signature === staticKey) return;
    if (!force && now - staticAt < STATIC_INTERVAL_MOVING_MS) return;
    staticAt = now;
    staticKey = signature;
    beginProjection(w, h);
    staticCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    staticCtx.globalAlpha = 1;
    staticCtx.fillStyle = '#0d0e11';
    staticCtx.fillRect(0, 0, w, h);
    staticCtx.strokeStyle = '#1c1f24';
    staticCtx.lineWidth = 1;
    for (let i = 1; i < 10; i++) {
      staticCtx.beginPath(); staticCtx.moveTo(i * w / 10, 0); staticCtx.lineTo(i * w / 10, h); staticCtx.stroke();
      staticCtx.beginPath(); staticCtx.moveTo(0, i * h / 10); staticCtx.lineTo(w, i * h / 10); staticCtx.stroke();
    }

    if (state.layers.points) {
      const points = state.cloud?.points || [];
      const stride = Math.max(1, Math.ceil(points.length / CLOUD_TARGET_POINTS));
      staticCtx.fillStyle = '#8c9098';
      for (let i = 0; i < points.length; i += stride) {
        const p = points[i], q = project(p, w, h);
        staticCtx.globalAlpha = 0.12 + 0.55 * Math.max(0, Math.min(1, +p[3] || 0.4));
        staticCtx.fillRect(q[0], q[1], 1.25, 1.25);
      }
      staticCtx.globalAlpha = 1;
    }

    if (state.layers.terrain) {
      const b = bounds(), minY = b.min[1], range = Math.max(0.01, b.max[1] - b.min[1]);
      for (const p of state.terrain || []) {
        const q = project(p, w, h), fraction = (p[1] - minY) / range;
        staticCtx.globalAlpha = 0.18 + 0.45 * p[3];
        staticCtx.fillStyle = 'hsl(' + (125 + fraction * 75) + ' 45% ' + (32 + fraction * 25) + '%)';
        staticCtx.fillRect(q[0] - 1.5, q[1] - 1.5, 3, 3);
      }
      staticCtx.globalAlpha = 1;
    }

    if (state.layers.anchors) {
      for (const anchor of state.map?.anchors || []) {
        const p = anchorPos(anchor);
        if (!p) continue;
        staticDot(p, 5, '#d59a4a', '#f2efe8');
        const x = axisFromMatrix(anchor, [.5, 0, 0]);
        const y = axisFromMatrix(anchor, [0, .5, 0]);
        const z = axisFromMatrix(anchor, [0, 0, -.5]);
        if (x) staticLine(p, x, '#df6f67', 2);
        if (y) staticLine(p, y, '#68cf83', 2);
        if (z) staticLine(p, z, '#7895ff', 2);
        staticLabel(add(p, [0, .12, 0]), 'anchor ' + short(anchor.id) + ' ' + (anchor.featureQuality || ''), '#d59a4a');
      }
    }
  }

  function resizeStatic(rect, dpr) {
    const width = Math.max(1, Math.floor(rect.width * dpr));
    const height = Math.max(1, Math.floor(rect.height * dpr));
    const changed = staticCanvas.width !== width || staticCanvas.height !== height;
    if (changed) { staticCanvas.width = width; staticCanvas.height = height; }
    return changed;
  }

  draw = function smoothDraw(now = performance.now()) {
    const rect = scene.getBoundingClientRect();
    const dpr = Math.max(1, Math.min(2, devicePixelRatio || 1));
    const staticResized = resizeStatic(rect, dpr);
    drawStatic(now, rect.width, rect.height, dpr, staticResized);

    beginProjection(rect.width, rect.height);
    dynamicCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    dynamicCtx.clearRect(0, 0, rect.width, rect.height);
    const live = renderLive(now) || state.live || { clients: [], tracks: [], serverTimeMs: Date.now() };
    const liveNow = live.serverTimeMs || Date.now();
    if (state.layers.clients) for (const client of live.clients || []) drawClient(client, liveNow);
    if (state.layers.tracks) for (const track of live.tracks || []) drawTrack(track, liveNow);

    if (now - hudAt >= HUD_INTERVAL_MS) {
      hudAt = now;
      $('hud').textContent = fps + ' FPS · ' + (state.cloud?.sampledPoints || 0).toLocaleString() + ' scan pts / ' +
        (state.cloud?.totalPoints || 0).toLocaleString() + ' total · ' + state.terrain.length.toLocaleString() +
        ' terrain cells · ' + (state.map?.anchors || []).length + ' anchors · ' +
        (live.clients || []).length + ' clients · ' + (live.tracks || []).length + ' live tracks · cached world / predicted motion';
    }
  };

  function tick(now) {
    fpsFrames += 1;
    if (now - fpsAt >= 500) {
      fps = Math.round(fpsFrames * 1000 / Math.max(1, now - fpsAt));
      fpsFrames = 0;
      fpsAt = now;
    }
    draw(now);
    requestAnimationFrame(tick);
  }

  setInterval(() => {
    if (state.authenticated && state.map && !state.paused) refreshLive();
  }, LIVE_POLL_MS);
  requestAnimationFrame(tick);
})();
</script>`;
  return page.replace('</body>', patch + '</body>');
}
