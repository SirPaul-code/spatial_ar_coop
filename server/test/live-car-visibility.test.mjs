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
      resolve(socket);
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
      cleanup();
      resolve(value);
    };
    const cleanup = () => { clearTimeout(timer); socket.removeEventListener('message', listener); };
    socket.addEventListener('message', listener);
  });
}

function car(id, x) {
  return {
    id,
    label: 'car',
    confidence: 0.92,
    position: [x, 0, 8],
    velocity: [0, 0, 0],
    extentMeters: [1.85, 1.5, 4.4],
    observedAtMs: Date.now(),
    hitCount: 4
  };
}

test('cars disappear when the last observing device stops publishing them', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-live-cars-'));
  const app = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, apiToken: 'test-token', adminToken: 'test-token', stdout: false, trackTtlMs: 500 });
  let sensorA;
  let sensorB;
  let viewer;
  try {
    const address = await app.start();
    const base = `http://127.0.0.1:${address.port}`;
    const headers = { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' };
    const create = await fetch(`${base}/api/v1/maps`, { method: 'POST', headers, body: JSON.stringify({ id: 'road', name: 'Road', createdBy: 'test' }) });
    assert.equal(create.status, 201);

    const wsBase = `ws://127.0.0.1:${address.port}/ws?token=test-token&mapId=road`;
    sensorA = await openWebSocket(`${wsBase}&clientId=sensor-a&role=sensor`);
    sensorB = await openWebSocket(`${wsBase}&clientId=sensor-b&role=sensor`);
    viewer = await openWebSocket(`${wsBase}&clientId=viewer&role=viewer`);

    const batchA = nextMessage(viewer, (value) => value.type === 'track_batch' && value.sourceId === 'sensor-a');
    sensorA.send(JSON.stringify({ type: 'track_batch', sequence: 1, replaceSource: true, tracks: [car('car-a', 1)] }));
    await batchA;

    const batchB = nextMessage(viewer, (value) => value.type === 'track_batch' && value.sourceId === 'sensor-b');
    sensorB.send(JSON.stringify({ type: 'track_batch', sequence: 1, replaceSource: true, tracks: [car('car-b', 1.2)] }));
    await batchB;

    let state = await (await fetch(`${base}/api/v1/maps/road/live-state`, { headers })).json();
    assert.equal(state.tracks.filter((track) => track.label === 'car').length, 2);

    const expiredA = nextMessage(viewer, (value) => value.type === 'tracks_expired' && value.trackKeys.includes('sensor-a:car-a'));
    sensorA.send(JSON.stringify({ type: 'track_batch', sequence: 2, replaceSource: true, tracks: [] }));
    await expiredA;
    state = await (await fetch(`${base}/api/v1/maps/road/live-state`, { headers })).json();
    assert.equal(state.tracks.filter((track) => track.label === 'car').length, 1);

    const expiredB = nextMessage(viewer, (value) => value.type === 'tracks_expired' && value.trackKeys.includes('sensor-b:car-b'));
    sensorB.send(JSON.stringify({ type: 'track_batch', sequence: 2, replaceSource: true, tracks: [] }));
    await expiredB;
    state = await (await fetch(`${base}/api/v1/maps/road/live-state`, { headers })).json();
    assert.equal(state.tracks.filter((track) => track.label === 'car').length, 0);
  } finally {
    sensorA?.close();
    sensorB?.close();
    viewer?.close();
    await app.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
