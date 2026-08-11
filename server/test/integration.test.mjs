import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSpatialServer } from '../src/app.mjs';

function openWebSocket(url, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const timer = setTimeout(() => { cleanup(); socket.close(); reject(new Error('Timed out waiting for WebSocket welcome')); }, timeoutMs);
    const onMessage = (event) => {
      const value = JSON.parse(event.data);
      if (value.type !== 'welcome') return;
      cleanup();
      resolve({ socket, welcome: value });
    };
    const onError = (event) => { cleanup(); reject(event.error ?? new Error('WebSocket connection failed')); };
    const cleanup = () => {
      clearTimeout(timer);
      socket.removeEventListener('message', onMessage);
      socket.removeEventListener('error', onError);
    };
    socket.addEventListener('message', onMessage);
    socket.addEventListener('error', onError, { once: true });
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

test('REST map API and WebSocket multi-track snapshots relay end to end', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-integration-'));
  const app = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, apiToken: 'test-token', adminToken: 'test-token', stdout: false, trackTtlMs: 500 });
  let sensor;
  let viewer;
  try {
    const address = await app.start();
    const base = `http://127.0.0.1:${address.port}`;
    const headers = { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' };
    const create = await fetch(`${base}/api/v1/maps`, { method: 'POST', headers, body: JSON.stringify({ id: 'demo', name: 'Demo', createdBy: 'test' }) });
    assert.equal(create.status, 201);
    const list = await fetch(`${base}/api/v1/maps`, { headers });
    assert.equal((await list.json()).maps.length, 1);

    const wsBase = `ws://127.0.0.1:${address.port}/ws?token=test-token&mapId=demo`;
    ({ socket: sensor } = await openWebSocket(`${wsBase}&clientId=sensor&role=participant`));
    ({ socket: viewer } = await openWebSocket(`${wsBase}&clientId=viewer&role=participant`));

    const firstBatchPromise = nextMessage(viewer, (value) => value.type === 'track_batch' && value.sequence === 1);
    sensor.send(JSON.stringify({
      type: 'track_batch',
      sequence: 1,
      replaceSource: true,
      tracks: [
        { id: 'bird-1', label: 'bird', confidence: .87, position: [1, 0, 2], velocity: [.1, 0, 0], observedAtMs: Date.now() },
        { id: 'bird-2', label: 'bird', confidence: .81, position: [2, 0, 3], velocity: [0, 0, 0], observedAtMs: Date.now() }
      ]
    }));
    const firstBatch = await firstBatchPromise;
    assert.equal(firstBatch.replaceSource, true);
    assert.equal(firstBatch.tracks.length, 2);
    assert.deepEqual(firstBatch.tracks.map((track) => track.key).sort(), ['sensor:bird-1', 'sensor:bird-2']);
    assert.ok(firstBatch.tracks.every((track) => track.sourceId === 'sensor'));

    // The next complete snapshot contains only bird-1. bird-2 must disappear immediately from
    // every viewer and from the server live snapshot instead of lingering until TRACK_TTL_MS.
    const expiredPromise = nextMessage(viewer, (value) => value.type === 'tracks_expired');
    const secondBatchPromise = nextMessage(viewer, (value) => value.type === 'track_batch' && value.sequence === 2);
    sensor.send(JSON.stringify({
      type: 'track_batch',
      sequence: 2,
      replaceSource: true,
      tracks: [{ id: 'bird-1', label: 'bird', confidence: .9, position: [1.1, 0, 2], velocity: [.1, 0, 0], observedAtMs: Date.now() }]
    }));
    const expired = await expiredPromise;
    assert.deepEqual(expired.trackKeys, ['sensor:bird-2']);
    const secondBatch = await secondBatchPromise;
    assert.equal(secondBatch.tracks.length, 1);
    assert.equal(secondBatch.tracks[0].key, 'sensor:bird-1');

    sensor.send(JSON.stringify({ type: 'client_pose', pose: { position: [1, 1.5, 2], rotation: [0, 0, 0, 1], tracking: 'TRACKING' } }));
    sensor.send(JSON.stringify({ type: 'status', state: 'reporting', detail: 'object detection enabled' }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    const live = await fetch(`${base}/api/v1/maps/demo/live-state`, { headers });
    assert.equal(live.status, 200);
    const state = await live.json();
    assert.equal(state.clients.length, 2);
    assert.equal(state.tracks.length, 1);
    assert.equal(state.tracks[0].key, 'sensor:bird-1');
    assert.equal(state.clients.find((client) => client.clientId === 'sensor')?.status?.state, 'reporting');
  } finally {
    sensor?.close();
    viewer?.close();
    await app.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
