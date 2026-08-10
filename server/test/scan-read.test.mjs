import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { createSpatialServer } from '../src/app.mjs';

function sac1(points, capturedAtMs = 1_700_000_000_000) {
  const raw = Buffer.alloc(16 + points.length * 16);
  raw.write('SAC1', 0, 'ascii');
  raw.writeInt32LE(points.length, 4);
  raw.writeBigInt64LE(BigInt(capturedAtMs), 8);
  points.forEach((point, index) => {
    const offset = 16 + index * 16;
    raw.writeFloatLE(point[0], offset);
    raw.writeFloatLE(point[1], offset + 4);
    raw.writeFloatLE(point[2], offset + 8);
    raw.writeFloatLE(point[3] ?? 1, offset + 12);
  });
  return zlib.gzipSync(raw);
}

test('scan chunks are authenticated, cacheable and previewed with bounded deterministic sampling', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-scan-read-'));
  const app = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, apiToken: 'scan-token', adminToken: 'scan-token', stdout: false });
  try {
    const address = await app.start();
    const base = `http://127.0.0.1:${address.port}`;
    const auth = { Authorization: 'Bearer scan-token' };
    const jsonHeaders = { ...auth, 'Content-Type': 'application/json' };
    assert.equal((await fetch(`${base}/api/v1/maps`, { method: 'POST', headers: jsonHeaders, body: JSON.stringify({ id: 'scan-map', name: 'Scan map' }) })).status, 201);

    const first = sac1([[0, 0, 0, .8], [1, 0, 1, .9], [2, .2, 2, 1]], 1_700_000_000_000);
    const second = sac1([[3, .1, 3, .7], [4, 0, 4, .6]], 1_700_000_001_000);
    for (const [id, body] of [['a', first], ['b', second]]) {
      const upload = await fetch(`${base}/api/v1/maps/scan-map/scan-chunks`, {
        method: 'POST', headers: { ...auth, 'Content-Type': 'application/octet-stream', 'X-Chunk-Id': id, 'X-Device-Id': 'phone-a' }, body
      });
      assert.equal(upload.status, 201);
    }

    // Map-specific routes intentionally return 404 without a valid map credential so callers
    // cannot distinguish a private map from a nonexistent one.
    assert.equal((await fetch(`${base}/api/v1/maps/scan-map/scan-chunks`)).status, 404);
    const page1Response = await fetch(`${base}/api/v1/maps/scan-map/scan-chunks?limit=1`, { headers: auth });
    assert.equal(page1Response.status, 200);
    const page1 = await page1Response.json();
    assert.equal(page1.totalChunks, 2);
    assert.equal(page1.totalPoints, 5);
    assert.equal(page1.chunks.length, 1);
    assert.equal(page1.chunks[0].deviceId, 'phone-a');
    assert.ok(page1.chunks[0].sha256.length === 64);
    assert.equal(page1.nextCursor, '1');
    const page2 = await (await fetch(`${base}/api/v1/maps/scan-map/scan-chunks?limit=1&cursor=${page1.nextCursor}`, { headers: auth })).json();
    assert.equal(page2.chunks.length, 1);
    assert.equal(page2.nextCursor, null);

    const chunk = await fetch(`${base}/api/v1/maps/scan-map/scan-chunks/a`, { headers: auth });
    assert.equal(chunk.status, 200);
    const etag = chunk.headers.get('etag');
    assert.ok(etag);
    assert.deepEqual(Buffer.from(await chunk.arrayBuffer()), first);
    const cached = await fetch(`${base}/api/v1/maps/scan-map/scan-chunks/a`, { headers: { ...auth, 'If-None-Match': etag } });
    assert.equal(cached.status, 304);

    const previewA = await (await fetch(`${base}/api/v1/maps/scan-map/point-cloud?maxPoints=100`, { headers: auth })).json();
    const previewB = await (await fetch(`${base}/api/v1/maps/scan-map/point-cloud?maxPoints=100`, { headers: auth })).json();
    assert.equal(previewA.totalPoints, 5);
    assert.equal(previewA.sampledPoints, 5);
    assert.deepEqual(previewA, previewB);
    assert.deepEqual(previewA.bounds.min, [0, 0, 0]);
    assert.deepEqual(previewA.bounds.max, [4, .20000000298023224, 4]);

    const map = await (await fetch(`${base}/api/v1/maps/scan-map`, { headers: auth })).json();
    assert.equal(map.scan.chunkCount, 2);
    assert.equal(map.scan.pointCount, 5);

    assert.equal((await fetch(`${base}/api/v1/maps/scan-map/scan-chunks/%2e%2e`, { headers: auth })).status, 404);
  } finally {
    await app.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
