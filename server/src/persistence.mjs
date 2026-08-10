import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { safeId } from './protocol.mjs';

export class MapStore {
  constructor({ dataDir, logger, maxScanChunkBytes = 8 * 1024 * 1024 }) {
    this.dataDir = dataDir;
    this.logger = logger;
    this.maxScanChunkBytes = maxScanChunkBytes;
    this.mapsDir = path.join(dataDir, 'maps');
    this.chunksDir = path.join(dataDir, 'chunks');
    fs.mkdirSync(this.mapsDir, { recursive: true });
    fs.mkdirSync(this.chunksDir, { recursive: true });
  }

  listMaps() {
    const files = fs.readdirSync(this.mapsDir, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
      .map((entry) => path.join(this.mapsDir, entry.name));
    return files.map((file) => this.#readJson(file)).sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  }

  getMap(mapId) {
    const id = safeId(mapId, 'mapId');
    const file = this.#mapFile(id);
    if (!fs.existsSync(file)) return null;
    return this.#readJson(file);
  }

  createMap(payload) {
    const id = safeId(payload?.id, 'map.id');
    if (this.getMap(id)) return this.getMap(id);
    const now = new Date().toISOString();
    const settings = payload?.settings ?? {};
    const map = {
      id,
      name: String(payload?.name ?? id).slice(0, 120),
      status: 'MAPPING',
      createdBy: String(payload?.createdBy ?? 'unknown').slice(0, 96),
      createdAt: now,
      updatedAt: now,
      rootAnchorId: null,
      groundY: null,
      settings: {
        anchorTtlDays: clampInt(settings.anchorTtlDays, 1, 1, 365),
        minAnchorSpacingMeters: clampNumber(settings.minAnchorSpacingMeters, 3, 0.5, 50),
        autoAnchor: settings.autoAnchor !== false
      },
      anchors: [],
      scan: { chunkCount: 0, pointCount: 0, bytes: 0, lastChunkAt: null }
    };
    this.#writeMap(map);
    this.logger.info('map_created', { mapId: id, name: map.name, createdBy: map.createdBy });
    return map;
  }

  patchMap(mapId, patch) {
    const map = this.#requireMap(mapId);
    if (patch.name !== undefined) map.name = String(patch.name).slice(0, 120);
    if (patch.status !== undefined && ['MAPPING', 'READY', 'ARCHIVED'].includes(String(patch.status))) map.status = String(patch.status);
    if (patch.rootAnchorId !== undefined) map.rootAnchorId = patch.rootAnchorId === null ? null : safeId(patch.rootAnchorId, 'rootAnchorId');
    if (patch.groundY !== undefined) map.groundY = patch.groundY === null ? null : clampNumber(patch.groundY, 0, -10000, 10000);
    if (patch.settings && typeof patch.settings === 'object') {
      map.settings = {
        anchorTtlDays: clampInt(patch.settings.anchorTtlDays, map.settings.anchorTtlDays, 1, 365),
        minAnchorSpacingMeters: clampNumber(patch.settings.minAnchorSpacingMeters, map.settings.minAnchorSpacingMeters, 0.5, 50),
        autoAnchor: patch.settings.autoAnchor ?? map.settings.autoAnchor
      };
    }
    map.updatedAt = new Date().toISOString();
    this.#writeMap(map);
    this.logger.info('map_updated', { mapId: map.id, status: map.status, rootAnchorId: map.rootAnchorId, groundY: map.groundY });
    return map;
  }

  upsertAnchor(mapId, payload) {
    const map = this.#requireMap(mapId);
    const id = safeId(payload?.id, 'anchor.id');
    const matrix = Array.isArray(payload?.siteFromAnchor) && payload.siteFromAnchor.length === 16
      ? payload.siteFromAnchor.map((value) => Number(value))
      : identityMatrix();
    if (!matrix.every(Number.isFinite)) throw badRequest('INVALID_MATRIX', 'siteFromAnchor must contain 16 finite numbers');
    const anchor = {
      id,
      cloudAnchorId: String(payload?.cloudAnchorId ?? '').slice(0, 1024),
      siteFromAnchor: matrix,
      status: ['PENDING', 'HOSTING', 'HOSTED', 'FAILED', 'NEEDS_RESCAN'].includes(String(payload?.status)) ? String(payload.status) : 'PENDING',
      featureQuality: ['UNKNOWN', 'INSUFFICIENT', 'SUFFICIENT', 'GOOD'].includes(String(payload?.featureQuality)) ? String(payload.featureQuality) : 'UNKNOWN',
      lastError: payload?.lastError == null ? null : String(payload.lastError).slice(0, 512),
      updatedAt: normalizeTimestamp(payload?.updatedAt)
    };
    const index = map.anchors.findIndex((entry) => entry.id === id);
    if (index >= 0) map.anchors[index] = anchor; else map.anchors.push(anchor);
    if (!map.rootAnchorId && anchor.status === 'HOSTED') map.rootAnchorId = id;
    map.updatedAt = new Date().toISOString();
    this.#writeMap(map);
    this.logger.info('anchor_upserted', { mapId: map.id, anchorId: id, status: anchor.status, quality: anchor.featureQuality });
    return anchor;
  }

  storeScanChunk(mapId, { chunkId, deviceId, body }) {
    const map = this.#requireMap(mapId);
    const id = safeId(chunkId, 'chunkId');
    const device = safeId(deviceId, 'deviceId');
    if (!Buffer.isBuffer(body) || body.length === 0) throw badRequest('EMPTY_CHUNK', 'Scan chunk body is empty');
    if (body.length > this.maxScanChunkBytes) throw badRequest('CHUNK_TOO_LARGE', `Scan chunk exceeds ${this.maxScanChunkBytes} bytes`);
    const dir = path.join(this.chunksDir, map.id);
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${id}.sac.gz`);
    if (fs.existsSync(file)) {
      return { duplicate: true, id, bytes: fs.statSync(file).size };
    }
    const decoded = decodeScanChunk(body);
    const temp = `${file}.${process.pid}.${Date.now()}.tmp`;
    fs.writeFileSync(temp, body, { flag: 'wx' });
    fs.renameSync(temp, file);
    map.scan.chunkCount += 1;
    map.scan.pointCount += decoded.pointCount;
    map.scan.bytes += body.length;
    map.scan.lastChunkAt = new Date().toISOString();
    map.updatedAt = map.scan.lastChunkAt;
    this.#writeMap(map);
    this.logger.info('scan_chunk_stored', { mapId: map.id, chunkId: id, deviceId: device, pointCount: decoded.pointCount, bytes: body.length });
    return { duplicate: false, id, bytes: body.length, pointCount: decoded.pointCount };
  }

  deleteMap(mapId) {
    const map = this.#requireMap(mapId);
    fs.rmSync(this.#mapFile(map.id), { force: true });
    fs.rmSync(path.join(this.chunksDir, map.id), { recursive: true, force: true });
    this.logger.warn('map_deleted', { mapId: map.id });
    return map;
  }

  metrics() {
    const maps = this.listMaps();
    return {
      maps: maps.length,
      anchors: maps.reduce((sum, map) => sum + map.anchors.length, 0),
      scanChunks: maps.reduce((sum, map) => sum + map.scan.chunkCount, 0),
      scanPoints: maps.reduce((sum, map) => sum + map.scan.pointCount, 0),
      scanBytes: maps.reduce((sum, map) => sum + map.scan.bytes, 0)
    };
  }

  #requireMap(mapId) {
    const map = this.getMap(mapId);
    if (!map) {
      const error = new Error(`Map ${mapId} was not found`);
      error.statusCode = 404;
      error.code = 'MAP_NOT_FOUND';
      throw error;
    }
    return map;
  }

  #mapFile(mapId) { return path.join(this.mapsDir, `${mapId}.json`); }
  #readJson(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  #writeMap(map) { atomicWriteJson(this.#mapFile(map.id), map); }
}

export function decodeScanChunk(compressed) {
  let raw;
  try {
    raw = zlib.gunzipSync(compressed);
  } catch {
    throw badRequest('INVALID_GZIP', 'Scan chunk is not valid gzip data');
  }
  if (raw.length < 16 || raw.subarray(0, 4).toString('ascii') !== 'SAC1') {
    throw badRequest('INVALID_SCAN_FORMAT', 'Scan chunk does not start with SAC1');
  }
  const pointCount = raw.readUInt32LE(4);
  const expected = 16 + pointCount * 16;
  if (raw.length !== expected) throw badRequest('INVALID_SCAN_LENGTH', `Expected ${expected} decoded bytes, got ${raw.length}`);
  return { pointCount, capturedAtMs: Number(raw.readBigInt64LE(8)), raw };
}

function atomicWriteJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = `${file}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  fs.renameSync(temp, file);
}

function normalizeTimestamp(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return new Date(value).toISOString();
  const date = new Date(value ?? Date.now());
  return Number.isNaN(date.getTime()) ? new Date().toISOString() : date.toISOString();
}

function clampNumber(value, fallback, min, max) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback;
}

function clampInt(value, fallback, min, max) { return Math.trunc(clampNumber(value, fallback, min, max)); }
function identityMatrix() { return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; }
function badRequest(code, message) { const error = new Error(message); error.statusCode = 400; error.code = code; return error; }
