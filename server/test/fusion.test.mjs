import test from 'node:test';
import assert from 'node:assert/strict';
import { SpatialFusionEngine } from '../src/fusion.mjs';

function track({ key, sourceId, id, label = 'car', position, confidence = .85, uncertaintyMeters = .3 }) {
  return {
    key, sourceId, id, label, confidence, position,
    velocity: [1, 0, 0], uncertaintyMeters,
    extentMeters: label === 'car' ? [1.9, 1.5, 4.4] : [.6, 1.7, .45],
    yawRadians: 0, observedAtMs: 1000, serverReceivedAtMs: 1000,
    spatialMethod: 'raw-depth+terrain', hitCount: 8
  };
}

test('fuses nearby same-class observations from different sensors with provenance', () => {
  const engine = new SpatialFusionEngine({ historyIntervalMs: 100 });
  const live = engine.ingest('demo', {
    serverTimeMs: 1000,
    clients: [],
    tracks: [
      track({ key: 'a:t1', sourceId: 'a', id: 't1', position: [0, 0, 0], confidence: .82, uncertaintyMeters: .28 }),
      track({ key: 'b:t7', sourceId: 'b', id: 't7', position: [.45, 0, .15], confidence: .91, uncertaintyMeters: .22 })
    ]
  }, 1000);
  assert.equal(live.fusedTracks.length, 1);
  const fused = live.fusedTracks[0];
  assert.equal(fused.label, 'car');
  assert.equal(fused.sourceCount, 2);
  assert.deepEqual(fused.sourceIds, ['a', 'b']);
  assert.equal(fused.quality, 'MULTI_SENSOR');
  assert.ok(fused.confidence > .9);
  assert.ok(fused.uncertaintyMeters < .5);
  assert.equal(fused.observations.length, 2);
});

test('does not collapse two tracks emitted by the same sensor into one entity', () => {
  const engine = new SpatialFusionEngine();
  const live = engine.ingest('demo', {
    serverTimeMs: 1000,
    clients: [],
    tracks: [
      track({ key: 'a:t1', sourceId: 'a', id: 't1', position: [0, 0, 0] }),
      track({ key: 'a:t2', sourceId: 'a', id: 't2', position: [.5, 0, .2] })
    ]
  }, 1000);
  assert.equal(live.fusedTracks.length, 2);
  assert.ok(live.fusedTracks.every((item) => item.sourceCount === 1));
});

test('keeps stable fused ids across successive snapshots', () => {
  const engine = new SpatialFusionEngine();
  const first = engine.ingest('demo', {
    serverTimeMs: 1000,
    clients: [],
    tracks: [track({ key: 'a:t1', sourceId: 'a', id: 't1', position: [1, 0, 2] })]
  }, 1000).fusedTracks[0];
  const second = engine.ingest('demo', {
    serverTimeMs: 1500,
    clients: [],
    tracks: [track({ key: 'a:t1', sourceId: 'a', id: 't1', position: [1.25, 0, 2.1] })]
  }, 1500).fusedTracks[0];
  assert.equal(second.id, first.id);
  assert.equal(second.firstSeenAtMs, first.firstSeenAtMs);
});

test('maintains a bounded replay buffer and downsamples on request', () => {
  const engine = new SpatialFusionEngine({ historyWindowMs: 10_000, historyIntervalMs: 100, maxFrames: 20 });
  for (let i = 0; i < 30; i += 1) {
    const now = 1000 + i * 100;
    engine.ingest('demo', { serverTimeMs: now, clients: [], tracks: [] }, now);
  }
  const replay = engine.replay('demo', { lookbackMs: 10_000, limit: 5 });
  assert.equal(replay.frames.length, 5);
  assert.ok(replay.totalBufferedFrames <= 20);
  assert.ok(replay.frames[0].serverTimeMs < replay.frames.at(-1).serverTimeMs);
});
