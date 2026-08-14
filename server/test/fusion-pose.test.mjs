import test from 'node:test';
import assert from 'node:assert/strict';
import { SpatialFusionEngine } from '../src/fusion.mjs';

const pose = (xOffset = 0) => [
  [0, xOffset, 1.68, 0, .94],
  [11, -.22 + xOffset, 1.42, 0, .92],
  [12, .22 + xOffset, 1.42, 0, .92],
  [13, -.38 + xOffset, 1.12, .03, .90],
  [14, .38 + xOffset, 1.12, -.03, .90],
  [15, -.48 + xOffset, .86, .08, .88],
  [16, .48 + xOffset, .86, -.08, .88],
  [23, -.14 + xOffset, .91, 0, .93],
  [24, .14 + xOffset, .91, 0, .93],
  [25, -.13 + xOffset, .48, 0, .91],
  [26, .13 + xOffset, .48, 0, .91],
  [27, -.12 + xOffset, .05, 0, .90],
  [28, .12 + xOffset, .05, 0, .90]
];

function person(sourceId, id, x, joints) {
  return {
    key: sourceId + ':' + id,
    sourceId,
    id,
    label: 'person',
    confidence: .88,
    position: [x, 0, 4],
    velocity: [.2, 0, 0],
    uncertaintyMeters: .24,
    extentMeters: [.6, 1.72, .45],
    yawRadians: 0,
    observedAtMs: 10_000,
    serverReceivedAtMs: 10_000,
    spatialMethod: 'raw-depth+terrain',
    depthConfidence: .82,
    hitCount: 9,
    poseJoints: joints
  };
}

test('fuses shared-site person articulation without flattening it out of the entity payload', () => {
  const engine = new SpatialFusionEngine();
  const live = engine.ingest('demo', {
    serverTimeMs: 10_000,
    clients: [],
    tracks: [
      person('phone-a', 't1', 1.00, pose(0)),
      person('phone-b', 't8', 1.08, pose(.01))
    ]
  }, 10_000);

  assert.equal(live.fusedTracks.length, 1);
  const fused = live.fusedTracks[0];
  assert.equal(fused.label, 'person');
  assert.equal(fused.sourceCount, 2);
  assert.equal(fused.quality, 'MULTI_SENSOR');
  assert.ok(fused.poseJoints.length >= 10);
  assert.equal(fused.observations[0].poseJointCount, pose().length);
  const head = fused.poseJoints.find((joint) => joint[0] === 0);
  assert.ok(head);
  assert.ok(head[2] > 1.5 && head[2] < 1.9, 'head remains relative to fused ground-contact root');

  const replay = engine.replay('demo', { lookbackMs: 10_000, limit: 10 });
  assert.ok(replay.frames[0].fusedTracks[0].poseJoints.length >= 10, 'replay retains pose articulation');
});
