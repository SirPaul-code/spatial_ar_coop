import path from 'node:path';

function intEnv(name, fallback, min, max) {
  const parsed = Number.parseInt(process.env[name] ?? '', 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

export function loadConfig(overrides = {}) {
  const cwd = process.cwd();
  const configuredAdminToken = overrides.adminToken ?? overrides.apiToken ?? process.env.ADMIN_TOKEN ?? process.env.SPATIAL_ADMIN_TOKEN ?? process.env.DEMO_API_TOKEN ?? '';
  return {
    host: overrides.host ?? process.env.HOST ?? '0.0.0.0',
    port: overrides.port ?? intEnv('PORT', 8080, 1, 65535),
    dataDir: path.resolve(overrides.dataDir ?? process.env.DATA_DIR ?? path.join(cwd, 'data')),
    adminToken: String(configuredAdminToken).trim(),
    serverId: String(overrides.serverId ?? process.env.SPATIAL_SERVER_ID ?? '').trim(),
    serverName: String(overrides.serverName ?? process.env.SPATIAL_SERVER_NAME ?? 'Spatial AR Server').trim(),
    publicBaseUrl: String(overrides.publicBaseUrl ?? process.env.SPATIAL_PUBLIC_URL ?? '').trim().replace(/\/+$/, ''),
    logLevel: overrides.logLevel ?? process.env.LOG_LEVEL ?? 'info',
    trackTtlMs: overrides.trackTtlMs ?? intEnv('TRACK_TTL_MS', 1800, 250, 60000),
    maxScanChunkBytes: overrides.maxScanChunkBytes ?? intEnv('MAX_SCAN_CHUNK_BYTES', 8 * 1024 * 1024, 1024, 64 * 1024 * 1024),
    maxJsonBytes: overrides.maxJsonBytes ?? 512 * 1024,
    wsMaxPayloadBytes: overrides.wsMaxPayloadBytes ?? 256 * 1024
  };
}
