import path from 'node:path';

function intEnv(name, fallback, min, max) {
  const parsed = Number.parseInt(process.env[name] ?? '', 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

export function loadConfig(overrides = {}) {
  const cwd = process.cwd();
  return {
    host: overrides.host ?? process.env.HOST ?? '0.0.0.0',
    port: overrides.port ?? intEnv('PORT', 8080, 1, 65535),
    dataDir: path.resolve(overrides.dataDir ?? process.env.DATA_DIR ?? path.join(cwd, 'data')),
    apiToken: overrides.apiToken ?? process.env.DEMO_API_TOKEN ?? '',
    adminToken: overrides.adminToken ?? process.env.ADMIN_TOKEN ?? process.env.DEMO_API_TOKEN ?? '',
    logLevel: overrides.logLevel ?? process.env.LOG_LEVEL ?? 'info',
    trackTtlMs: overrides.trackTtlMs ?? intEnv('TRACK_TTL_MS', 1800, 250, 60000),
    maxScanChunkBytes: overrides.maxScanChunkBytes ?? intEnv('MAX_SCAN_CHUNK_BYTES', 8 * 1024 * 1024, 1024, 64 * 1024 * 1024),
    maxJsonBytes: overrides.maxJsonBytes ?? 512 * 1024,
    wsMaxPayloadBytes: overrides.wsMaxPayloadBytes ?? 256 * 1024
  };
}
