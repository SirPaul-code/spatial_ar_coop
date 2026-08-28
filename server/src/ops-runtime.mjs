import { SpatialFusionEngine } from './fusion.mjs';
import { opsPage } from './ops-page-smooth.mjs';
import { adminPage as smoothAdminPage } from './admin-page-smooth.mjs';

export function installOpsLayer(app, {
  historyWindowMs = 10 * 60 * 1000,
  sampleIntervalMs = 500,
  maxFrames = 1200
} = {}) {
  if (!app?.server || !app?.hub || !app?.store || !app?.identity) {
    throw new Error('installOpsLayer requires a spatial server app instance');
  }

  const baseHandlers = app.server.listeners('request');
  if (baseHandlers.length !== 1) {
    throw new Error(`Expected exactly one base HTTP request handler, found ${baseHandlers.length}`);
  }
  const baseHandler = baseHandlers[0];
  const engine = new SpatialFusionEngine({
    historyWindowMs,
    historyIntervalMs: sampleIntervalMs,
    maxFrames
  });

  app.server.removeListener('request', baseHandler);
  const wrappedHandler = (request, response) => {
    Promise.resolve(handleOpsRequest({ request, response, app, engine }))
      .then((handled) => {
        if (!handled && !response.writableEnded) baseHandler(request, response);
      })
      .catch((error) => {
        const status = Number(error?.statusCode) || 500;
        app.logger?.[status >= 500 ? 'error' : 'warn']?.('ops_http_error', { error, path: request.url, status });
        if (!response.headersSent) {
          sendJson(response, status, {
            error: {
              code: error?.code ?? 'OPS_INTERNAL_ERROR',
              message: status >= 500 ? 'Ops request failed' : String(error?.message ?? 'Request failed')
            }
          });
        } else if (!response.writableEnded) response.end();
      });
  };
  app.server.on('request', wrappedHandler);

  // Replay stays deliberately compact at ~2 Hz. The live /ops-state endpoint may be polled much
  // faster by the browser and calls engine.ingest directly, so live rendering and replay storage
  // have independent cadences.
  const sample = () => {
    const maps = app.store.listMaps();
    const liveMapIds = new Set();
    for (const map of maps) {
      liveMapIds.add(map.id);
      engine.ingest(map.id, app.hub.snapshot(map.id));
    }
    engine.retain(liveMapIds);
  };
  const timer = setInterval(sample, sampleIntervalMs);
  timer.unref?.();

  return {
    engine,
    sample,
    close() {
      clearInterval(timer);
      app.server.removeListener('request', wrappedHandler);
      if (!app.server.listeners('request').includes(baseHandler)) app.server.on('request', baseHandler);
    }
  };
}

async function handleOpsRequest({ request, response, app, engine }) {
  const url = new URL(request.url, 'http://localhost');
  if (request.method === 'GET' && url.pathname === '/') {
    sendText(response, 200, smoothAdminPage(), 'text/html; charset=utf-8');
    return true;
  }
  if (request.method === 'GET' && url.pathname === '/ops') {
    sendText(response, 200, opsPage(), 'text/html; charset=utf-8');
    return true;
  }

  const stateMatch = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/ops-state$/);
  if (stateMatch && request.method === 'GET') {
    const map = authorizeMap({ app, mapId: stateMatch[1], request, url });
    const state = engine.ingest(map.id, app.hub.snapshot(map.id));
    sendJson(response, 200, {
      ...state,
      serverId: app.identity.publicInfo().serverId,
      map: {
        id: map.id,
        name: map.name,
        status: map.status,
        anchorCount: Array.isArray(map.anchors) ? map.anchors.length : 0,
        scanPointCount: Number(map.scan?.pointCount ?? 0)
      }
    });
    return true;
  }

  const replayMatch = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/mission-replay$/);
  if (replayMatch && request.method === 'GET') {
    const map = authorizeMap({ app, mapId: replayMatch[1], request, url });
    const seconds = clampNumber(url.searchParams.get('seconds'), 300, 1, 600);
    const limit = clampNumber(url.searchParams.get('limit'), 600, 1, 2000);
    sendJson(response, 200, engine.replay(map.id, { lookbackMs: seconds * 1000, limit }));
    return true;
  }

  return false;
}

function authorizeMap({ app, mapId, request, url }) {
  const map = app.store.getMap(mapId);
  const token = requestToken(request, url.searchParams.get('mapKey') ?? url.searchParams.get('token') ?? '');
  if (!map || !app.identity.isMapAuthorized(mapId, token)) {
    const error = new Error('Map is unavailable or the map key is invalid');
    error.statusCode = 404;
    error.code = 'MAP_UNAVAILABLE';
    throw error;
  }
  return map;
}

function requestToken(request, queryToken = '') {
  const authorization = String(request.headers.authorization ?? '');
  const bearer = authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
  const adminHeader = String(request.headers['x-admin-token'] ?? '');
  const mapHeader = String(request.headers['x-spatial-map-key'] ?? '');
  return bearer || adminHeader || mapHeader || String(queryToken ?? '');
}

function clampNumber(value, fallback, min, max) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(number)));
}

function sendJson(response, status, value) {
  const text = JSON.stringify(value);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(text),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  response.end(text);
}

function sendText(response, status, text, type) {
  response.writeHead(status, {
    'Content-Type': type,
    'Content-Length': Buffer.byteLength(text),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer'
  });
  response.end(text);
}
