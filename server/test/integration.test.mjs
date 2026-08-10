import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSpatialServer } from '../src/app.mjs';

function openWebSocket(url) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.addEventListener('open', () => resolve(socket), { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
}
function nextMessage(socket, predicate = () => true, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { cleanup(); reject(new Error('Timed out waiting for WebSocket message')); }, timeoutMs);
    const listener = (event) => {
      const value = JSON.parse(event.data);
      if (!predicate(value)) return;
      cleanup(); resolve(value);
    };
    const cleanup = () => { clearTimeout(timer); socket.removeEventListener('message', listener); };
    socket.addEventListener('message', listener);
  });
}

test('REST map API and WebSocket track relay work end to end', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-integration-'));
  const app = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, apiToken: 'test-token', adminToken: 'test-token', stdout: false, trackTtlMs: 500 });
  try {
    const address = await app.start();
    const base = `http://127.0.0.1:${address.port}`;
    const headers = { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' };
    const create = await fetch(`${base}/api/v1/maps`, { method: 'POST', headers, body: JSON.stringify({ id: 'demo', name: 'Demo', createdBy: 'test' }) });
    assert.equal(create.status, 201);
    const list = await fetch(`${base}/api/v1/maps`, { headers });
    assert.equal((await list.json()).maps.length, 1);

    const wsBase = `ws://127.0.0.1:${address.port}/ws?token=test-token&mapId=demo`;
    const sensor = await openWebSocket(`${wsBase}&clientId=sensor&role=sensor`);
    const viewer = await openWebSocket(`${wsBase}&clientId=viewer&role=viewer`);
    await nextMessage(sensor, (value) => value.type === 'welcome');
    await nextMessage(viewer, (value) => value.type === 'welcome');
    const received = nextMessage(viewer, (value) => value.type === 'track_batch');
    sensor.send(JSON.stringify({ type: 'track_batch', sequence: 1, tracks: [{ id: 't1', label: 'person', confidence: .9, position: [1, 0, 2], velocity: [0, 0, 0], observedAtMs: Date.now() }] }));
    const batch = await received;
    assert.equal(batch.tracks[0].key, 'sensor:t1');
    assert.equal(batch.tracks[0].sourceId, 'sensor');
    sensor.close(); viewer.close();
  } finally {
    await app.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
