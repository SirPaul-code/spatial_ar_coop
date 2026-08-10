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
    // Register the message listener immediately: the server sends welcome as soon as the
    // connection is accepted, potentially before a later 'open' callback can attach one.
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

test('REST map API and WebSocket track relay work end to end', async () => {
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
    ({ socket: sensor } = await openWebSocket(`${wsBase}&clientId=sensor&role=sensor`));
    ({ socket: viewer } = await openWebSocket(`${wsBase}&clientId=viewer&role=viewer`));

    const received = nextMessage(viewer, (value) => value.type === 'track_batch');
    sensor.send(JSON.stringify({ type: 'track_batch', sequence: 1, tracks: [{ id: 't1', label: 'person', confidence: .9, position: [1, 0, 2], velocity: [0, 0, 0], observedAtMs: Date.now() }] }));
    const batch = await received;
    assert.equal(batch.tracks[0].key, 'sensor:t1');
    assert.equal(batch.tracks[0].sourceId, 'sensor');

    sensor.send(JSON.stringify({ type: 'client_pose', pose: { position: [1, 1.5, 2], rotation: [0, 0, 0, 1], trackingState: 'TRACKING' } }));
    sensor.send(JSON.stringify({ type: 'status', state: 'reporting', detail: 'object detection enabled' }));
    await new Promise((resolve) => setTimeout(resolve, 20));
    const live = await fetch(`${base}/api/v1/maps/demo/live-state`, { headers });
    assert.equal(live.status, 200);
    const state = await live.json();
    assert.equal(state.clients.length, 2);
    assert.equal(state.tracks.length, 1);
    assert.equal(state.clients.find((client) => client.clientId === 'sensor')?.status?.state, 'reporting');
  } finally {
    sensor?.close();
    viewer?.close();
    await app.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
