import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { safeId } from './protocol.mjs';

const MAX_DECODED_CHUNK_BYTES = 32 * 1024 * 1024;

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
    return files.map((file) => normalizeMap(this.#readJson(file)))
      .sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  }

  getMap(mapId) {
    const id = safeId(mapId, 'mapId');
    const file = this.#mapFile(id);
    if (!fs.existsSync(file)) return null;
    return normalizeMap(this.#readJson(file));
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
    const dir = this.#chunkDir(map.id);
    fs.mkdirSync(dir, { recursive: true });
    const file = this.#chunkFile(map.id, id);
    if (fs.existsSync(file)) {
      return { duplicate: true, id, bytes: fs.statSync(file).size };
    }
    const decoded = decodeScanChunk(body);
    const digest = sha256(body);
    const temp = `${file}.${process.pid}.${Date.now()}.tmp`;
    fs.writeFileSync(temp, body, { flag: 'wx' });
    fs.renameSync(temp, file);
    atomicWriteJson(this.#chunkMetaFile(map.id, id), {
      id,
      deviceId: device,
      pointCount: decoded.pointCount,
      compressedBytes: body.length,
      sha256: digest,
      capturedAt: new Date(decoded.capturedAtMs).toISOString()
    });
    map.scan.chunkCount += 1;
    map.scan.pointCount += decoded.pointCount;
    map.scan.bytes += body.length;
    map.scan.lastChunkAt = new Date().toISOString();
    map.updatedAt = map.scan.lastChunkAt;
    this.#writeMap(map);
    this.logger.info('scan_chunk_stored', { mapId: map.id, chunkId: id, deviceId: device, pointCount: decoded.pointCount, bytes: body.length });
    return { duplicate: false, id, bytes: body.length, pointCount: decoded.pointCount };
  }

  listScanChunks(mapId, { cursor = '0', limit = 50 } = {}) {
    const map = this.#requireMap(mapId);
    const offset = clampInt(cursor, 0, 0, Number.MAX_SAFE_INTEGER);
    const pageSize = clampInt(limit, 50, 1, 100);
    const files = this.#chunkFiles(map.id);
    const pageFiles = files.slice(offset, offset + pageSize);
    const chunks = pageFiles.map((file) => this.#chunkMetadata(map.id, file));
    const nextOffset = offset + pageFiles.length;
    return {
      chunks,
      totalChunks: files.length,
      totalPoints: map.scan.pointCount,
      nextCursor: nextOffset < files.length ? String(nextOffset) : null
    };
  }

  getScanChunk(mapId, chunkId) {
    const map = this.#requireMap(mapId);
    const id = safeId(chunkId, 'chunkId');
    const file = this.#chunkFile(map.id, id);
    if (!fs.existsSync(file)) throw notFound('CHUNK_NOT_FOUND', `Scan chunk ${id} was not found`);
    const body = fs.readFileSync(file);
    const decoded = decodeScanChunk(body);
    const metadata = this.#chunkMetadata(map.id, file, decoded, body);
    return { body, metadata };
  }

  pointCloudPreview(mapId, maxPoints = 20000) {
    const map = this.#requireMap(mapId);
    const cap = clampInt(maxPoints, 20000, 100, 50000);
    const files = this.#chunkFiles(map.id);
    const totalPoints = Math.max(0, Number(map.scan.pointCount) || 0);
    if (files.length === 0 || totalPoints === 0) {
      return { mapId: map.id, totalPoints, sampledPoints: 0, bounds: null, points: [] };
    }
    const stride = Math.max(1, Math.ceil(totalPoints / cap));
    const points = [];
    const min = [Infinity, Infinity, Infinity];
    const max = [-Infinity, -Infinity, -Infinity];
    let globalIndex = 0;
    for (const file of files) {
      const decoded = decodeScanChunk(fs.readFileSync(file));
      for (let index = 0; index < decoded.pointCount; index += 1) {
        const offset = 16 + index * 16;
        const x = decoded.raw.readFloatLE(offset);
        const y = decoded.raw.readFloatLE(offset + 4);
        const z = decoded.raw.readFloatLE(offset + 8);
        const q = decoded.raw.readFloatLE(offset + 12);
        if (![x, y, z, q].every(Number.isFinite)) throw badRequest('INVALID_SCAN_POINT', `Chunk ${path.basename(file)} contains a non-finite point`);
        min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
        max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
        if (globalIndex % stride === 0 && points.length < cap) points.push([x, y, z, q]);
        globalIndex += 1;
      }
    }
    return {
      mapId: map.id,
      totalPoints: globalIndex,
      sampledPoints: points.length,
      bounds: globalIndex ? { min, max } : null,
      points
    };
  }

  deleteMap(mapId) {
    const map = this.#requireMap(mapId);
    fs.rmSync(this.#mapFile(map.id), { force: true });
    fs.rmSync(this.#chunkDir(map.id), { recursive: true, force: true });
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

  #chunkFiles(mapId) {
    const dir = this.#chunkDir(mapId);
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith('.sac.gz'))
      .map((entry) => path.join(dir, entry.name))
      .sort((a, b) => path.basename(a).localeCompare(path.basename(b)));
  }

  #chunkMetadata(mapId, file, decodedValue = null, bodyValue = null) {
    const id = path.basename(file, '.sac.gz');
    const metaFile = this.#chunkMetaFile(mapId, id);
    const persisted = fs.existsSync(metaFile) ? this.#readJson(metaFile) : null;
    if (persisted?.sha256 && persisted?.pointCount != null && persisted?.compressedBytes != null) return persisted;
    const body = bodyValue ?? fs.readFileSync(file);
    const decoded = decodedValue ?? decodeScanChunk(body);
    return {
      id,
      deviceId: persisted?.deviceId ?? null,
      pointCount: decoded.pointCount,
      compressedBytes: body.length,
      sha256: sha256(body),
      capturedAt: new Date(decoded.capturedAtMs).toISOString()
    };
  }

  #requireMap(mapId) {
    const map = this.getMap(mapId);
    if (!map) throw notFound('MAP_NOT_FOUND', `Map ${mapId} was not found`);
    return map;
  }

  #mapFile(mapId) { return path.join(this.mapsDir, `${mapId}.json`); }
  #chunkDir(mapId) { return path.join(this.chunksDir, safeId(mapId, 'mapId')); }
  #chunkFile(mapId, chunkId) { return path.join(this.#chunkDir(mapId), `${safeId(chunkId, 'chunkId')}.sac.gz`); }
  #chunkMetaFile(mapId, chunkId) { return path.join(this.#chunkDir(mapId), `${safeId(chunkId, 'chunkId')}.json`); }
  #readJson(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  #writeMap(map) { atomicWriteJson(this.#mapFile(map.id), normalizeMap(map)); }
}

export function decodeScanChunk(compressed) {
  let raw;
  try {
    raw = zlib.gunzipSync(compressed, { maxOutputLength: MAX_DECODED_CHUNK_BYTES });
  } catch {
    throw badRequest('INVALID_GZIP', 'Scan chunk is not valid bounded gzip data');
  }
  if (raw.length < 16 || raw.subarray(0, 4).toString('ascii') !== 'SAC1') {
    throw badRequest('INVALID_SCAN_FORMAT', 'Scan chunk does not start with SAC1');
  }
  const pointCount = raw.readInt32LE(4);
  if (pointCount < 0) throw badRequest('INVALID_SCAN_COUNT', 'Scan point count cannot be negative');
  const expected = 16 + pointCount * 16;
  if (raw.length !== expected) throw badRequest('INVALID_SCAN_LENGTH', `Expected ${expected} decoded bytes, got ${raw.length}`);
  return { pointCount, capturedAtMs: Number(raw.readBigInt64LE(8)), raw };
}

function normalizeMap(map) {
  return {
    ...map,
    anchors: Array.isArray(map?.anchors) ? map.anchors : [],
    scan: {
      chunkCount: Math.max(0, Number(map?.scan?.chunkCount) || 0),
      pointCount: Math.max(0, Number(map?.scan?.pointCount) || 0),
      bytes: Math.max(0, Number(map?.scan?.bytes) || 0),
      lastChunkAt: map?.scan?.lastChunkAt ?? null
    }
  };
}

function atomicWriteJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = `${file}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(temp, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
  fs.renameSync(temp, file);
}

function sha256(buffer) { return crypto.createHash('sha256').update(buffer).digest('hex'); }
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
function notFound(code, message) { const error = new Error(message); error.statusCode = 404; error.code = code; return error; }
