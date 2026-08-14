import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { installOpsLayer } from '../src/ops-runtime.mjs';

function testApp() {
  const map = { id: 'demo', name: 'Demo', status: 'READY', anchors: [], scan: { pointCount: 1234 } };
  const server = http.createServer((request, response) => {
    response.writeHead(418, { 'Content-Type': 'text/plain' });
    response.end('base-handler');
  });
  return {
    server,
    store: { listMaps: () => [map], getMap: (id) => id === map.id ? map : null },
    identity: {
      isMapAuthorized: (mapId, token) => mapId === 'demo' && token === 'secret',
      publicInfo: () => ({ serverId: 'srv-test' })
    },
    hub: {
      snapshot: () => ({
        mapId: 'demo', serverTimeMs: Date.now(),
        clients: [
          { clientId: 'a', role: 'sensor', connected: true, pose: { position: [0, 1, 0], rotation: [0, 0, 0, 1], tracking: 'TRACKING' } },
          { clientId: 'b', role: 'sensor', connected: true, pose: { position: [2, 1, 0], rotation: [0, 0, 0, 1], tracking: 'TRACKING' } }
        ],
        tracks: [
          { key: 'a:t1', sourceId: 'a', id: 't1', label: 'car', confidence: .8, position: [1, 0, 2], velocity: [0, 0, 0], uncertaintyMeters: .3, extentMeters: [2, 1.5, 4], yawRadians: 0 },
          { key: 'b:t5', sourceId: 'b', id: 't5', label: 'car', confidence: .9, position: [1.3, 0, 2.1], velocity: [0, 0, 0], uncertaintyMeters: .25, extentMeters: [2, 1.5, 4], yawRadians: 0 }
        ]
      })
    },
    logger: { error() {}, warn() {} }
  };
}

test('ops layer preserves the base handler and exposes authorized fused state + replay', async () => {
  const app = testApp();
  const ops = installOpsLayer(app, { sampleIntervalMs: 100, historyWindowMs: 10_000, maxFrames: 30 });
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  const address = app.server.address();
  const base = `http://127.0.0.1:${address.port}`;
  try {
    const baseResponse = await fetch(base + '/healthz');
    assert.equal(baseResponse.status, 418);
    assert.equal(await baseResponse.text(), 'base-handler');

    const page = await fetch(base + '/ops');
    assert.equal(page.status, 200);
    assert.match(await page.text(), /SPATIAL OPS/);

    const denied = await fetch(base + '/api/v1/maps/demo/ops-state');
    assert.equal(denied.status, 404);

    ops.sample();
    const headers = { Authorization: 'Bearer secret' };
    const stateResponse = await fetch(base + '/api/v1/maps/demo/ops-state', { headers });
    assert.equal(stateResponse.status, 200);
    const state = await stateResponse.json();
    assert.equal(state.fusedTracks.length, 1);
    assert.equal(state.fusedTracks[0].sourceCount, 2);
    assert.equal(state.fusedTracks[0].quality, 'MULTI_SENSOR');

    await new Promise((resolve) => setTimeout(resolve, 120));
    ops.sample();
    const replayResponse = await fetch(base + '/api/v1/maps/demo/mission-replay?seconds=10&limit=10', { headers });
    assert.equal(replayResponse.status, 200);
    const replay = await replayResponse.json();
    assert.ok(replay.frames.length >= 1);
  } finally {
    ops.close();
    await new Promise((resolve) => app.server.close(resolve));
  }
});
