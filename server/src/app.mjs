import http from 'node:http';
import { loadConfig } from './config.mjs';
import { JsonLogger } from './logger.mjs';
import { MapStore } from './persistence.mjs';
import { RealtimeHub } from './websocket.mjs';
import { adminPage } from './admin-page.mjs';

export function createSpatialServer(overrides = {}) {
  const config = loadConfig(overrides);
  const logger = overrides.logger ?? new JsonLogger({ dataDir: config.dataDir, level: config.logLevel, stdout: overrides.stdout ?? true });
  const store = overrides.store ?? new MapStore({ dataDir: config.dataDir, logger, maxScanChunkBytes: config.maxScanChunkBytes });
  let hub;
  const server = http.createServer((request, response) => handleHttp({ request, response, config, logger, store, hub }));
  const authorize = (request, queryToken = '') => checkToken(config.apiToken, request, queryToken);
  hub = new RealtimeHub({ server, logger, trackTtlMs: config.trackTtlMs, maxPayload: config.wsMaxPayloadBytes, authorize });

  return {
    config, logger, store, hub, server,
    async start() {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(config.port, config.host, () => { server.off('error', reject); resolve(); });
      });
      const address = server.address();
      logger.info('server_started', { host: config.host, port: typeof address === 'object' ? address.port : config.port, dataDir: config.dataDir });
      return address;
    },
    async stop() {
      await hub.close();
      await new Promise((resolve) => server.close(() => resolve()));
      logger.info('server_stopped');
    }
  };
}

async function handleHttp({ request, response, config, logger, store, hub }) {
  const startedAt = Date.now();
  const url = new URL(request.url, 'http://localhost');
  setCommonHeaders(response);
  if (request.method === 'OPTIONS') return sendEmpty(response, 204);
  try {
    if (request.method === 'GET' && url.pathname === '/') return sendText(response, 200, adminPage(), 'text/html; charset=utf-8');
    if (request.method === 'GET' && url.pathname === '/healthz') return sendJson(response, 200, { ok: true, now: new Date().toISOString() });

    if (!checkToken(config.apiToken, request, url.searchParams.get('token') ?? '')) return sendError(response, 401, 'UNAUTHORIZED', 'A valid API token is required');

    if (request.method === 'GET' && url.pathname === '/api/v1/metrics') {
      return sendJson(response, 200, { ...store.metrics(), ...hub.metrics(), uptimeSeconds: Math.floor(process.uptime()) });
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/maps') return sendJson(response, 200, { maps: store.listMaps() });
    if (request.method === 'POST' && url.pathname === '/api/v1/maps') {
      const payload = await readJson(request, config.maxJsonBytes);
      return sendJson(response, 201, store.createMap(payload));
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/logs') {
      if (!checkToken(config.adminToken, request, url.searchParams.get('token') ?? '')) return sendError(response, 401, 'ADMIN_UNAUTHORIZED', 'A valid admin token is required');
      return sendJson(response, 200, { entries: logger.recent(Number(url.searchParams.get('limit') ?? 100)) });
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/events') {
      if (!checkToken(config.adminToken, request, url.searchParams.get('token') ?? '')) return sendError(response, 401, 'ADMIN_UNAUTHORIZED', 'A valid admin token is required');
      return streamLogs(request, response, logger);
    }

    const chunkItem = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/scan-chunks\/([a-zA-Z0-9._-]+)$/);
    if (chunkItem && request.method === 'GET') {
      const [, mapId, chunkId] = chunkItem;
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
      return sendJson(response, 200, store.pointCloudPreview(pointCloud[1], url.searchParams.get('maxPoints') ?? 20000));
    }

    const liveState = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)\/live-state$/);
    if (liveState && request.method === 'GET') {
      const map = store.getMap(liveState[1]);
      if (!map) return sendError(response, 404, 'MAP_NOT_FOUND', `Map ${liveState[1]} was not found`);
      return sendJson(response, 200, hub.snapshot(liveState[1]));
    }

    const match = url.pathname.match(/^\/api\/v1\/maps\/([a-zA-Z0-9._-]+)(?:\/(anchors|scan-chunks))?$/);
    if (match) {
      const [, mapId, child] = match;
      if (!child && request.method === 'GET') {
        const map = store.getMap(mapId);
        return map ? sendJson(response, 200, map) : sendError(response, 404, 'MAP_NOT_FOUND', `Map ${mapId} was not found`);
      }
      if (!child && request.method === 'PATCH') return sendJson(response, 200, store.patchMap(mapId, await readJson(request, config.maxJsonBytes)));
      if (!child && request.method === 'DELETE') return sendJson(response, 200, store.deleteMap(mapId));
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

function streamLogs(request, response, logger) {
  response.writeHead(200, {
    'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive',
    'X-Accel-Buffering': 'no-cache'
  });
  for (const entry of logger.recent(50)) response.write(`data: ${JSON.stringify(entry)}\n\n`);
  const unsubscribe = logger.subscribe((entry) => response.write(`data: ${JSON.stringify(entry)}\n\n`));
  request.on('close', unsubscribe);
}

function checkToken(expected, request, queryToken = '') {
  if (!expected) return true;
  const authorization = String(request.headers.authorization ?? '');
  const bearer = authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
  const header = String(request.headers['x-api-token'] ?? '');
  return timingSafeEqual(expected, bearer || header || queryToken);
}

function timingSafeEqual(expected, actual) {
  if (!actual || expected.length !== actual.length) return false;
  let difference = 0;
  for (let index = 0; index < expected.length; index += 1) difference |= expected.charCodeAt(index) ^ actual.charCodeAt(index);
  return difference === 0;
}

async function readJson(request, limit) {
  const body = await readBuffer(request, limit);
  if (!body.length) return {};
  try { return JSON.parse(body.toString('utf8')); }
  catch { const error = new Error('Request body is not valid JSON'); error.statusCode = 400; error.code = 'INVALID_JSON'; throw error; }
}

function readBuffer(request, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        const error = new Error(`Request body exceeds ${limit} bytes`); error.statusCode = 413; error.code = 'PAYLOAD_TOO_LARGE';
        reject(error); request.destroy(); return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });
}

function setCommonHeaders(response) {
  response.setHeader('Access-Control-Allow-Origin', '*');
  response.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, If-None-Match, X-API-Token, X-Chunk-Id, X-Device-Id');
  response.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
  response.setHeader('X-Content-Type-Options', 'nosniff');
}
function sendJson(response, status, value) { const text = JSON.stringify(value); response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) }); response.end(text); }
function sendError(response, status, code, message) { return sendJson(response, status, { error: { code, message } }); }
function sendText(response, status, text, type) { response.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(text) }); response.end(text); }
function sendBuffer(response, status, body, headers = {}) { response.writeHead(status, headers); response.end(body); }
function sendEmpty(response, status, headers = {}) { response.writeHead(status, headers); response.end(); }
