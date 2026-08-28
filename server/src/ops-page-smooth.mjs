import { opsPage as baseOpsPage } from './ops-page-hifi.mjs';

/**
 * Adds a render-only performance layer on top of the Hi-Fi Ops page.
 *
 * The live data contract stays unchanged. Static world geometry is rendered on a cached canvas,
 * while clients/entities continue on the requestAnimationFrame canvas. The wrapper also replaces
 * the fixed interpolation delay with a small critically-damped visual chase of the newest
 * velocity-extrapolated snapshot. Raw server state and mission replay are never modified.
 */
export function opsPage() {
  const page = baseOpsPage();
  const patch = String.raw`<script>
(() => {
  const scene = document.getElementById('scene');
  if (!scene || typeof state === 'undefined') return;

  const staticCanvas = document.createElement('canvas');
  staticCanvas.id = 'sceneStatic';
  staticCanvas.setAttribute('aria-hidden', 'true');
  staticCanvas.style.pointerEvents = 'none';
  staticCanvas.style.zIndex = '0';
  scene.style.zIndex = '1';
  scene.parentNode.insertBefore(staticCanvas, scene);
  const toolbar = document.querySelector('.toolbar');
  const hud = document.querySelector('.hud');
  const crosshair = document.querySelector('.crosshair');
  if (toolbar) toolbar.style.zIndex = '4';
  if (hud) hud.style.zIndex = '4';
  if (crosshair) crosshair.style.zIndex = '3';

  const staticCtx = staticCanvas.getContext('2d', { alpha: true });
  const dynamicCtx = scene.getContext('2d', { alpha: true });
  let projectionCache = null;
  let lastStaticKey = '';
  let lastStaticAt = -Infinity;
  let lastHudAt = -Infinity;
  let boundsCache = null;
  let boundsKey = '';
  let cloudKey = '';
  let cloudBuckets = [];
  let trailKey = '';
  let trailCache = [];
  let visualFrame = null;
  let visualFrameAt = performance.now();
  let uiPaintAt = -Infinity;

  const STATIC_MOVING_INTERVAL_MS = 32;
  const HUD_INTERVAL_MS = 180;
  const UI_PAINT_INTERVAL_MS = 480;
  const VISUAL_TIME_CONSTANT_SECONDS = 0.055;
  const MAX_RENDER_PREDICTION_MS = 180;
  const CLOUD_TARGET_POINTS = 12000;

  function resizeCanvas(canvas, rect, dpr) {
    const width = Math.max(1, Math.round(rect.width * dpr));
    const height = Math.max(1, Math.round(rect.height * dpr));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
      return true;
    }
    return false;
  }

  const originalWorldBounds = worldBounds;
  worldBounds = function cachedWorldBounds() {
    const pts = state.cloud?.points || [];
    const anchors = state.map?.anchors || [];
    const b = state.cloud?.bounds;
    const key = [state.map?.id || '', pts.length, anchors.length,
      b?.min?.join(',') || '', b?.max?.join(',') || ''].join('|');
    if (key !== boundsKey || !boundsCache) {
      boundsKey = key;
      boundsCache = originalWorldBounds();
    }
    return boundsCache;
  };

  function beginProjection(w, h) {
    if (state.view === 'top') {
      projectionCache = {
        mode: 'top', w, h,
        scale: Math.min(w, h) / state.top.scale,
        center: state.top.center.slice()
      };
      return;
    }
    const basis = cameraBasis();
    projectionCache = {
      mode: 'fly', w, h,
      basis,
      pos: state.camera.pos.slice(),
      focal: 0.5 * h / Math.tan(state.camera.fov * Math.PI / 360)
    };
  }

  project3 = function cachedProject3(value, w, h) {
    const p = v3(value);
    let cache = projectionCache;
    if (!cache || cache.w !== w || cache.h !== h || cache.mode !== state.view) {
      beginProjection(w, h);
      cache = projectionCache;
    }
    if (cache.mode === 'top') {
      const x = p[0] - cache.center[0];
      const z = p[2] - cache.center[2];
      return [w / 2 + x * cache.scale, h / 2 + z * cache.scale, cache.scale, 1];
    }
    const r = [p[0] - cache.pos[0], p[1] - cache.pos[1], p[2] - cache.pos[2]];
    const z = dot(r, cache.basis.forward);
    if (z < 0.06) return null;
    return [
      w / 2 + dot(r, cache.basis.right) * cache.focal / z,
      h / 2 - dot(r, cache.basis.up) * cache.focal / z,
      cache.focal / z,
      z
    ];
  };

  function buildCloudBuckets() {
    const pts = state.cloud?.points || [];
    const bounds = state.cloud?.bounds;
    const key = [state.map?.id || '', pts.length,
      bounds?.min?.join(',') || '', bounds?.max?.join(',') || ''].join('|');
    if (key === cloudKey) return;
    cloudKey = key;
    cloudBuckets = [];
    if (!pts.length) return;

    const minY = Number(bounds?.min?.[1] ?? -1);
    const maxY = Number(bounds?.max?.[1] ?? 1);
    const span = Math.max(0.1, maxY - minY);
    const stride = Math.max(1, Math.ceil(pts.length / CLOUD_TARGET_POINTS));
    const buckets = Array.from({ length: 24 }, () => []);
    for (let i = 0; i < pts.length; i += stride) {
      const p = pts[i];
      const q = Number(p?.[3] ?? 0.3);
      if (!p || q < 0.16) continue;
      const t = Math.max(0, Math.min(1, (Number(p[1]) - minY) / span));
      const hBucket = Math.min(5, Math.max(0, Math.floor(t * 6)));
      const qBucket = Math.min(3, Math.max(0, Math.floor(Math.min(0.999, q) * 4)));
      buckets[hBucket * 4 + qBucket].push(p);
    }
    cloudBuckets = buckets.map((points, index) => {
      const hBucket = Math.floor(index / 4);
      const qBucket = index % 4;
      const t = (hBucket + 0.5) / 6;
      const q = (qBucket + 0.5) / 4;
      return {
        points,
        color: 'hsla(' + (165 + 44 * t) + ',58%,' + (43 + 18 * t) + '%,' + (0.13 + 0.45 * q) + ')'
      };
    }).filter(bucket => bucket.points.length);
  }

  drawPoints = function optimizedDrawPoints(ctx, w, h) {
    if (!state.layers.points) return;
    buildCloudBuckets();
    ctx.save();
    for (const bucket of cloudBuckets) {
      ctx.fillStyle = bucket.color;
      for (const p of bucket.points) {
        const pr = project3(p, w, h);
        if (!pr || pr[0] < -4 || pr[0] > w + 4 || pr[1] < -4 || pr[1] > h + 4) continue;
        const radius = state.view === 'fly' ? Math.max(0.7, Math.min(2.2, pr[2] * 0.012)) : 1.15;
        ctx.fillRect(pr[0] - radius * 0.5, pr[1] - radius * 0.5, radius, radius);
      }
    }
    ctx.restore();
  };

  function rebuildTrailsIfNeeded() {
    const history = state.history || [];
    const newest = history.length ? Number(history[history.length - 1]?.serverTimeMs || 0) : 0;
    const key = history.length + '|' + newest;
    if (key === trailKey) return;
    trailKey = key;
    const by = new Map();
    if (history.length >= 2) {
      const stride = Math.max(1, Math.floor(history.length / 180));
      for (let i = 0; i < history.length; i += stride) {
        for (const track of history[i].fusedTracks || []) {
          let values = by.get(track.id);
          if (!values) by.set(track.id, values = []);
          values.push(v3(track.position));
        }
      }
    }
    trailCache = [...by.values()];
  }

  drawTrails = function cachedDrawTrails(ctx, w, h) {
    if (!state.layers.trails) return;
    rebuildTrailsIfNeeded();
    ctx.save();
    ctx.strokeStyle = 'rgba(112,170,255,.28)';
    ctx.lineWidth = 1.2;
    for (const points of trailCache) {
      if (points.length < 2) continue;
      let started = false;
      ctx.beginPath();
      for (const world of points) {
        const p = project3(world, w, h);
        if (!p) { started = false; continue; }
        if (!started) { ctx.moveTo(p[0], p[1]); started = true; }
        else ctx.lineTo(p[0], p[1]);
      }
      ctx.stroke();
    }
    ctx.restore();
  };

  function latestPredictedFrame(now) {
    const samples = state.samples || [];
    if (!samples.length) return null;
    const latest = samples[samples.length - 1];
    const ageMs = Math.max(0, Math.min(MAX_RENDER_PREDICTION_MS, now - latest.at));
    return extrapolate(latest.data, ageMs);
  }

  liveFrame = function lowLatencyLiveFrame(now) {
    const target = latestPredictedFrame(now);
    if (!target) return null;
    if (!visualFrame) {
      visualFrame = target;
      visualFrameAt = now;
      return target;
    }
    const dt = Math.max(0.001, Math.min(0.05, (now - visualFrameAt) / 1000));
    visualFrameAt = now;
    const alpha = 1 - Math.exp(-dt / VISUAL_TIME_CONSTANT_SECONDS);
    visualFrame = mixFrame(visualFrame, target, alpha);
    return visualFrame;
  };

  const originalUpdateUi = updateUi;
  updateUi = function throttledUpdateUi(now) {
    if (now - uiPaintAt < UI_PAINT_INTERVAL_MS) return;
    uiPaintAt = now;
    return originalUpdateUi(now);
  };

  function staticViewKey(w, h) {
    const history = state.history || [];
    const newest = history.length ? Number(history[history.length - 1]?.serverTimeMs || 0) : 0;
    const view = state.view === 'top'
      ? ['top', ...state.top.center.map(v => Number(v).toFixed(3)), Number(state.top.scale).toFixed(3)]
      : ['fly', ...state.camera.pos.map(v => Number(v).toFixed(3)), Number(state.camera.yaw).toFixed(4), Number(state.camera.pitch).toFixed(4), Number(state.camera.fov).toFixed(2)];
    return [w.toFixed(1), h.toFixed(1), ...view,
      state.layers.points ? 1 : 0,
      state.layers.grid ? 1 : 0,
      state.layers.anchors ? 1 : 0,
      state.layers.trails ? 1 : 0,
      state.layers.labels ? 1 : 0,
      state.cloud?.points?.length || 0,
      state.map?.anchors?.length || 0,
      history.length, newest].join('|');
  }

  function drawStatic(now, rect, dpr, force) {
    const key = staticViewKey(rect.width, rect.height);
    if (!force && key === lastStaticKey) return;
    if (!force && now - lastStaticAt < STATIC_MOVING_INTERVAL_MS) return;
    lastStaticAt = now;
    lastStaticKey = key;
    staticCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    staticCtx.clearRect(0, 0, rect.width, rect.height);
    beginProjection(rect.width, rect.height);
    drawGrid(staticCtx, rect.width, rect.height);
    drawPoints(staticCtx, rect.width, rect.height);
    drawTrails(staticCtx, rect.width, rect.height);
    drawAnchors(staticCtx, rect.width, rect.height);
  }

  draw = function smoothDraw(now) {
    const rect = scene.getBoundingClientRect();
    const dpr = Math.max(1, Math.min(2, devicePixelRatio || 1));
    const staticResized = resizeCanvas(staticCanvas, rect, dpr);
    resizeCanvas(scene, rect, dpr);
    drawStatic(now, rect, dpr, staticResized);

    dynamicCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
    dynamicCtx.clearRect(0, 0, rect.width, rect.height);
    beginProjection(rect.width, rect.height);
    const frame = currentFrame(now) || {};
    drawLinks(dynamicCtx, rect.width, rect.height, frame);
    drawClients(dynamicCtx, rect.width, rect.height, frame);
    drawTracks(dynamicCtx, rect.width, rect.height, frame);

    if (now - lastHudAt >= HUD_INTERVAL_MS) {
      lastHudAt = now;
      const fusion = frame.fusion || {};
      const camera = state.camera.pos;
      const hudEl = document.getElementById('sceneHud');
      if (hudEl) hudEl.textContent = (state.mode === 'live' ? 'LIVE' : 'REPLAY') + ' · ' + state.fps + ' FPS · ' +
        Number(fusion.connectedClientCount ?? frame.clients?.length ?? 0) + ' sensors · ' +
        Number(fusion.rawObservationCount ?? frame.rawTrackCount ?? 0) + ' observations → ' +
        Number(fusion.fusedEntityCount ?? frame.fusedTracks?.length ?? 0) + ' entities · camera ' +
        camera.map(value => fmt(value, 1)).join(' / ') + ' · cached world + 60 FPS entities';
    }
  };
})();
</script>`;
  return page.replace('</body>', patch + '</body>');
}
