import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { safeId } from './protocol.mjs';

const SCHEMA_VERSION = 1;

export class ServerIdentityStore {
  constructor({ dataDir, logger, configuredAdminToken = '', configuredServerId = '', serverName = 'Spatial AR Server' }) {
    this.file = path.join(dataDir, 'server.json');
    this.logger = logger;
    fs.mkdirSync(dataDir, { recursive: true });

    const existing = readJson(this.file) ?? {};
    const generatedAdminToken = !configuredAdminToken && !existing.adminToken;
    this.state = {
      schemaVersion: SCHEMA_VERSION,
      serverId: normalizeServerId(configuredServerId || existing.serverId || `srv_${crypto.randomBytes(10).toString('hex')}`),
      serverName: String(serverName || existing.serverName || 'Spatial AR Server').slice(0, 120),
      adminToken: String(configuredAdminToken || existing.adminToken || randomSecret('sar_admin_')),
      mapKeys: normalizeMapKeys(existing.mapKeys)
    };
    this.#persist();
    this.logger?.info?.('server_identity_ready', {
      serverId: this.state.serverId,
      serverName: this.state.serverName,
      adminTokenSource: configuredAdminToken ? 'environment' : generatedAdminToken ? 'generated' : 'persisted'
    });
  }

  publicInfo() {
    return {
      serverId: this.state.serverId,
      serverName: this.state.serverName,
      protocolVersion: 1,
      auth: 'admin-plus-map-key'
    };
  }

  adminToken() { return this.state.adminToken; }

  isAdmin(candidate) {
    return secureEqual(this.state.adminToken, String(candidate ?? ''));
  }

  rotateAdminToken() {
    this.state.adminToken = randomSecret('sar_admin_');
    this.#persist();
    this.logger?.warn?.('admin_token_rotated', { serverId: this.state.serverId });
    return this.state.adminToken;
  }

  mapKey(mapId) {
    const id = safeId(mapId, 'mapId');
    let key = this.state.mapKeys[id];
    if (!key) {
      key = randomSecret('sar_map_');
      this.state.mapKeys[id] = key;
      this.#persist();
      this.logger?.info?.('map_key_created', { mapId: id });
    }
    return key;
  }

  rotateMapKey(mapId) {
    const id = safeId(mapId, 'mapId');
    const key = randomSecret('sar_map_');
    this.state.mapKeys[id] = key;
    this.#persist();
    this.logger?.warn?.('map_key_rotated', { mapId: id });
    return key;
  }

  isMapAuthorized(mapId, candidate) {
    const token = String(candidate ?? '');
    return this.isAdmin(token) || secureEqual(this.mapKey(mapId), token);
  }

  invite(mapId, serverUrl = '') {
    const id = safeId(mapId, 'mapId');
    const mapKey = this.mapKey(id);
    const normalizedUrl = String(serverUrl ?? '').trim().replace(/\/+$/, '');
    const params = new URLSearchParams({
      serverId: this.state.serverId,
      mapId: id,
      key: mapKey
    });
    if (normalizedUrl) params.set('url', normalizedUrl);
    return {
      serverId: this.state.serverId,
      serverName: this.state.serverName,
      serverUrl: normalizedUrl || null,
      mapId: id,
      mapKey,
      deepLink: `spatialar://join?${params.toString()}`
    };
  }

  deleteMapKey(mapId) {
    const id = safeId(mapId, 'mapId');
    if (this.state.mapKeys[id]) {
      delete this.state.mapKeys[id];
      this.#persist();
    }
  }

  #persist() {
    const temp = `${this.file}.${process.pid}.${Date.now()}.tmp`;
    fs.writeFileSync(temp, `${JSON.stringify(this.state, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    fs.renameSync(temp, this.file);
    try { fs.chmodSync(this.file, 0o600); } catch { /* best effort on non-POSIX filesystems */ }
  }
}

function readJson(file) {
  if (!fs.existsSync(file)) return null;
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch { return null; }
}

function normalizeServerId(value) {
  const text = String(value ?? '').trim();
  if (!/^[a-zA-Z0-9._-]{4,96}$/.test(text)) throw new Error('SPATIAL_SERVER_ID must match [a-zA-Z0-9._-] and be 4-96 characters');
  return text;
}

function normalizeMapKeys(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const result = {};
  for (const [key, secret] of Object.entries(value)) {
    if (/^[a-zA-Z0-9._-]{1,96}$/.test(key) && typeof secret === 'string' && secret.length >= 24) result[key] = secret;
  }
  return result;
}

function randomSecret(prefix) {
  return `${prefix}${crypto.randomBytes(32).toString('base64url')}`;
}

function secureEqual(expected, actual) {
  if (!expected || !actual) return false;
  const left = Buffer.from(expected);
  const right = Buffer.from(actual);
  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}
