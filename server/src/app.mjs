import crypto from 'node:crypto';
import http from 'node:http';
import QRCode from 'qrcode';
import { loadConfig } from './config.mjs';
import { JsonLogger } from './logger.mjs';
import { MapStore } from './persistence.mjs';
import { RealtimeHub } from './websocket.mjs';
import { adminPage } from './admin-page.mjs';
import { ServerIdentityStore } from './identity.mjs';

export function createSpatialServer(overrides = {}) {
  const config = loadConfig(overrides);
  const logger = overrides.logger ?? new JsonLogger({ dataDir: config.dataDir, level: config.logLevel, stdout: overrides.stdout ?? true });
  const store = overrides.store ?? new MapStore({ dataDir: config.dataDir, logger, maxScanChunkBytes: config.maxScanChunkBytes });
  const identity = overrides.identity ?? new ServerIdentityStore({
    dataDir: config.dataDir,
    logger,
    configuredAdminToken: config.adminToken,
    configuredServerId: config.serverId,
    serverName: config.serverName
  });

  let hub;
  const server = http.createServer((request, response) => handleHttp({ request, response, config, logger, store, hub, identity }));
  hub = new RealtimeHub({
    server,
    logger,
    trackTtlMs: config.trackTtlMs,
    maxPayload: config.wsMaxPayloadBytes,
    authorize: (request, queryToken, mapId) => {
      if (!store.getMap(mapId)) return false;
      return identity.isMapAuthorized(mapId, requestToken(request, queryToken));
    }
  });

  return {
    config, logger, store, hub, identity, server,
    async start() {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(config.port, config.host, () => { server.off('error', reject); resolve(); });
      });
      const address = server.address();
      logger.info('server_started', {
        serverId: identity.publicInfo().serverId,
        host: config.host,
        port: typeof address === 'object' ? address.port : config.port,
        dataDir: config.dataDir
      });
      return address;
    },
    async stop() {
      await hub.close();
      await new Promise((resolve) => server.close(() => resolve()));
      logger.info('server_stopped');
    }
  };
}

async function handleHttp({ request, response, config, logger, store, hub, identity }) {
  const startedAt = Date.now();
  const url = new URL(request.url, 'http://localhost');
  setCommonHeaders(response);
  if (request.method === 'OPTIONS') return sendEmpty(response, 204);

  try {
    if (request.method === 'GET' && url.pathname === '/') return sendText(response, 200, adminPage(), 'text/html; charset=utf-8');
    if (request.method === 'GET' && url.pathname === '/healthz') return sendJson(response, 200, { ok: true, now: new Date().toISOString() });
    if (request.method === 'GET' && url.pathname === '/api/v1/info') {
      return sendJson(response, 200, {
        ...identity.publicInfo(),
        version: process.env.npm_package_version ?? 'dev'
      });
    }

    const adminToken = requestToken(request, url.searchParams.get('token') ?? '');
    const isAdmin = identity.isAdmin(adminToken);

    if (request.method === 'GET' && url.pathname === '/api/v1/metrics') {
      if (!isAdmin) return unauthorized(response, 'ADMIN_UNAUTHORIZED', 'Admin token required');
      return sendJson(response, 200, { ...store.metrics(), ...hub.metrics(), uptimeSeconds: Math.floor(process.uptime()) });
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/logs') {
      if (!isAdmin) return unauthorized(response, 'ADMIN_UNAUTHORIZED', 'Admin token required');
      return sendJson(response, 200, { entries: logger.recent(Number(url.searchParams.get('limit') ?? 100)) });
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/events') {
      if (!isAdmin) return unauthorized(response, 'ADMIN_UNAUTHORIZED', 'Admin token required');
      return streamLogs(request, response, logger);
    }

    if (url.pathname === '/api/v1/maps') {
      if (!isAdmin) return unauthorized(response, 'ADMIN_UNAUTHORIZED', 'Admin token required');
      if (request.method === 'GET') {
        const maps = store.listMaps().map((map) => ({ ...map, accessKey: identity.mapKey(map.id) }));
        return sendJson(response, 200, { server: identity.publicInfo(), maps });
      }
      if (request.method === 'POST') {
        const payload = await readJson(request, config.maxJsonBytes);
        if (!payload.id) payload.id = `map-${crypto.randomUUID()}`;
        const map = store.createMap(payload);
        const accessKey = identity.mapKey(map.id);
        return sendJson(response, 201, {
          ...map,
          serverId: identity.publicInfo().serverId,
          accessKey,
          invite: identity.invite(map.id, config.publicBaseUrl)
        });
      }
    }

    const rotateMatch = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/rotate-key$/);
    if (rotateMatch && request.method === 'POST') {
      if (!isAdmin) return unauthorized(response, 'ADMIN_UNAUTHORIZED', 'Admin token required to rotate a map key');
      const map = store.getMap(rotateMatch[1]);
      if (!map) return sendError(response, 404, 'MAP_NOT_FOUND', 'Map not found');
      identity.rotateMapKey(map.id);
      hub.disconnectMap(map.id);
      return sendJson(response, 200, {
        map: withServerId(map, identity),
        invite: identity.invite(map.id, config.publicBaseUrl)
      });
    }

    const inviteQrMatch = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/invite-qr\.svg$/);
    if (inviteQrMatch && request.method === 'GET') {
      const map = authorizeMap({ mapId: inviteQrMatch[1], request, url, store, identity });
      const invite = identity.invite(map.id, config.publicBaseUrl);
      const svg = await QRCode.toString(invite.deepLink, {
        type: 'svg',
        errorCorrectionLevel: 'M',
        margin: 2,
        width: 360,
        color: { dark: '#17181A', light: '#F2EFE8' }
      });
      return sendText(response, 200, svg, 'image/svg+xml; charset=utf-8', {
        'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'"
      });
    }

    const inviteMatch = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/invite$/);
    if (inviteMatch && request.method === 'GET') {
      const map = authorizeMap({ mapId: inviteMatch[1], request, url, store, identity });
      return sendJson(response, 200, {
        map: withServerId(map, identity),
        invite: identity.invite(map.id, config.publicBaseUrl)
      });
    }

    const chunkItem = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/scan-chunks\/([a-zA-Z0-9._-]+)$/);
    if (chunkItem && request.method === 'GET') {
      const [, mapId, chunkId] = chunkItem;
      authorizeMap({ mapId, request, url, store, identity });
      const { body, metadata } = store.getScanChunk(mapId, chunkId);
      const etag = `"${metadata.sha256}"`;
      if (String(request.headers['if-none-match'] ?? '') === etag) return sendEmpty(response, 304, { ETag: etag });
      return sendBuffer(response, 200, body, {
        'Content-Type': 'application/gzip',
        'Content-Length': body.length,
        'Cache-Control': 'private, max-age=31536000, immutable',
        ETag: etag,
        'X-Scan-Point-Count': String(metadata.pointCount)
      });
    }

    const pointCloud = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/point-cloud$/);
    if (pointCloud && request.method === 'GET') {
      authorizeMap({ mapId: pointCloud[1], request, url, store, identity });
      return sendJson(response, 200, store.pointCloudPreview(pointCloud[1], url.searchParams.get('maxPoints') ?? 20000));
    }

    const liveState = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/live-state$/);
    if (liveState && request.method === 'GET') {
      const map = authorizeMap({ mapId: liveState[1], request, url, store, identity });
      return sendJson(response, 200, { ...hub.snapshot(map.id), serverId: identity.publicInfo().serverId });
    }

    const match = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)(?:\/(anchors|scan-chunks))?$/);
    if (match) {
      const [, mapId, child] = match;
      if (!child && request.method === 'DELETE') {
        if (!isAdmin) return unauthorized(response, 'ADMIN_UNAUTHORIZED', 'Admin token required to delete a map');
        hub.disconnectMap(mapId, 4004, 'map deleted');
        const deleted = store.deleteMap(mapId);
        identity.deleteMapKey(mapId);
        return sendJson(response, 200, deleted);
      }

      const map = authorizeMap({ mapId, request, url, store, identity });
      if (!child && request.method === 'GET') return sendJson(response, 200, withServerId(map, identity));
      if (!child && request.method === 'PATCH') return sendJson(response, 200, withServerId(store.patchMap(mapId, await readJson(request, config.maxJsonBytes)), identity));
      if (child === 'anchors' && request.method === 'POST') return sendJson(response, 200, store.upsertAnchor(mapId, await readJson(request, config.maxJsonBytes)));
      if (child === 'scan-chunks' && request.method === 'GET') {
        return sendJson(response, 200, store.listScanChunks(mapId, {
          cursor: url.searchParams.get('cursor') ?? '0',
          limit: url.searchParams.get('limit') ?? '50'
        }));
      }
      if (child === 'scan-chunks' && request.method === 'POST') {
        const chunkId = request.headers['x-chunk-id'];
        const deviceId = request.headers['x-device-id'];
        if (!chunkId || !deviceId) return sendError(response, 400, 'MISSING_HEADERS', 'X-Chunk-Id and X-Device-Id are required');
        const body = await readBuffer(request, config.maxScanChunkBytes);
        const result = store.storeScanChunk(mapId, { chunkId, deviceId, body });
        return sendJson(response, result.duplicate ? 200 : 201, result);
      }
    }

    return sendError(response, 404, 'NOT_FOUND', 'Route not found');
  } catch (error) {
    const status = Number(error.statusCode) || 500;
    logger[status >= 500 ? 'error' : 'warn']('http_error', { method: request.method, path: url.pathname, status, code: error.code, error });
    return sendError(response, status, error.code ?? 'INTERNAL_ERROR', status >= 500 ? 'Internal server error' : error.message);
  } finally {
    logger.debug('http_request', { method: request.method, path: url.pathname, durationMs: Date.now() - startedAt });
  }
}

function authorizeMap({ mapId, request, url, store, identity }) {
  const map = store.getMap(mapId);
  const token = requestToken(request, url.searchParams.get('mapKey') ?? url.searchParams.get('token') ?? '');
  if (!map || !identity.isMapAuthorized(mapId, token)) {
    const error = new Error('Map is unavailable or the map key is invalid');
    error.statusCode = 404;
    error.code = 'MAP_UNAVAILABLE';
    throw error;
  }
  return map;
}

function withServerId(map, identity) {
  return { ...map, serverId: identity.publicInfo().serverId };
}

function streamLogs(request, response, logger) {
  response.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no-cache'
  });
  for (const entry of logger.recent(50)) response.write(`data: ${JSON.stringify(entry)}\n\n`);
  const unsubscribe = logger.subscribe((entry) => response.write(`data: ${JSON.stringify(entry)}\n\n`));
  request.on('close', unsubscribe);
}

function requestToken(request, queryToken = '') {
  const authorization = String(request.headers.authorization ?? '');
  const bearer = authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
  const adminHeader = String(request.headers['x-admin-token'] ?? '');
  const mapHeader = String(request.headers['x-spatial-map-key'] ?? '');
  return bearer || adminHeader || mapHeader || String(queryToken ?? '');
}

async function readJson(request, limit) {
  const body = await readBuffer(request, limit);
  if (!body.length) return {};
  try { return JSON.parse(body.toString('utf8')); }
  catch {
    const error = new Error('Request body is not valid JSON');
    error.statusCode = 400;
    error.code = 'INVALID_JSON';
    throw error;
  }
}

function readBuffer(request, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        const error = new Error(`Request body exceeds ${limit} bytes`);
        error.statusCode = 413;
        error.code = 'PAYLOAD_TOO_LARGE';
        reject(error);
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });
}

function setCommonHeaders(response) {
  response.setHeader('Access-Control-Allow-Origin', '*');
  response.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, If-None-Match, X-Admin-Token, X-Spatial-Map-Key, X-Chunk-Id, X-Device-Id');
  response.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
  response.setHeader('X-Content-Type-Options', 'nosniff');
  response.setHeader('Referrer-Policy', 'no-referrer');
  response.setHeader('Cache-Control', 'no-store');
}

function unauthorized(response, code, message) { return sendError(response, 401, code, message); }
function sendJson(response, status, value) {
  const text = JSON.stringify(value);
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) });
  response.end(text);
}
function sendError(response, status, code, message) { return sendJson(response, status, { error: { code, message } }); }
function sendText(response, status, text, type, headers = {}) {
  response.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(text), ...headers });
  response.end(text);
}
function sendBuffer(response, status, body, headers = {}) { response.writeHead(status, headers); response.end(body); }
function sendEmpty(response, status, headers = {}) { response.writeHead(status, headers); response.end(); }
