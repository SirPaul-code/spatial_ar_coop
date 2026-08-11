import test from 'node:test';
import assert from 'node:assert/strict';
import { parseClientMessage, ProtocolError, validateClientIdentity } from '../src/protocol.mjs';

test('validates client identity including unified participant role', () => {
  assert.deepEqual(
    validateClientIdentity({ mapId: 'yard-1', clientId: 'phone_a', role: 'participant' }),
    { mapId: 'yard-1', clientId: 'phone_a', role: 'participant' }
  );
  assert.deepEqual(
    validateClientIdentity({ mapId: 'yard-1', clientId: 'legacy_sensor', role: 'sensor' }),
    { mapId: 'yard-1', clientId: 'legacy_sensor', role: 'sensor' }
  );
  assert.throws(() => validateClientIdentity({ mapId: '../etc', clientId: 'x', role: 'viewer' }), ProtocolError);
});

test('normalizes track batches, geometry and complete-source semantics', () => {
  const message = parseClientMessage(JSON.stringify({
    type: 'track_batch',
    sequence: 7,
    replaceSource: true,
    tracks: [{
      id: 't1', label: 'Car', confidence: 2, position: [1, 2, 3],
      extentMeters: [1.9, 1.55, 4.6], yawRadians: 0.75
    }]
  }));
  assert.equal(message.sequence, 7);
  assert.equal(message.replaceSource, true);
  assert.equal(message.tracks[0].label, 'car');
  assert.equal(message.tracks[0].confidence, 1);
  assert.deepEqual(message.tracks[0].velocity, [0, 0, 0]);
  assert.deepEqual(message.tracks[0].extentMeters, [1.9, 1.55, 4.6]);
  assert.equal(message.tracks[0].yawRadians, 0.75);
  assert.deepEqual(message.tracks[0].poseJoints, []);

  const legacy = parseClientMessage(JSON.stringify({
    type: 'track_batch', tracks: [{ id: 't1', label: 'bird', position: [0, 0, 0] }]
  }));
  assert.equal(legacy.replaceSource, false);
  assert.deepEqual(legacy.tracks[0].extentMeters, [0.45, 0.45, 0.55]);
  assert.equal(legacy.tracks[0].yawRadians, 0);
  assert.deepEqual(legacy.tracks[0].poseJoints, []);
});

test('accepts compact person skeletons and strips them from non-person tracks', () => {
  const pose = [
    [0, 0.0, 1.65, 0.02, 0.95],
    [11, -0.22, 1.42, 0.01, 0.91],
    [12, 0.22, 1.43, -0.01, 0.92],
    [23, -0.14, 0.92, 0.0, 0.96],
    [24, 0.14, 0.92, 0.0, 0.96],
    [27, -0.12, 0.04, 0.02, 0.89],
    [28, 0.12, 0.04, 0.02, 0.90]
  ];
  const message = parseClientMessage(JSON.stringify({
    type: 'track_batch',
    tracks: [
      { id: 'p1', label: 'person', position: [1, 0, 2], poseJoints: pose },
      { id: 'c1', label: 'car', position: [2, 0, 4], poseJoints: pose }
    ]
  }));
  assert.equal(message.tracks[0].poseJoints.length, pose.length);
  assert.deepEqual(message.tracks[0].poseJoints[0], pose[0]);
  assert.deepEqual(message.tracks[1].poseJoints, []);
});

test('rejects malformed or duplicate person skeleton joints', () => {
  assert.throws(() => parseClientMessage(JSON.stringify({
    type: 'track_batch',
    tracks: [{ id: 'p1', label: 'person', position: [0, 0, 0], poseJoints: [[99, 0, 1, 0, 1]] }]
  })), /invalid\/duplicate landmark index/);
  assert.throws(() => parseClientMessage(JSON.stringify({
    type: 'track_batch',
    tracks: [{ id: 'p1', label: 'person', position: [0, 0, 0], poseJoints: [[11, 0, 1, 0, 1], [11, 0, 1, 0, 1]] }]
  })), /invalid\/duplicate landmark index/);
});

test('clamps physically invalid extents instead of accepting absurd renderer geometry', () => {
  const message = parseClientMessage(JSON.stringify({
    type: 'track_batch',
    tracks: [{ id: 't1', label: 'car', position: [0, 0, 0], extentMeters: [-3, 999, 0.001], yawRadians: 99 }]
  }));
  assert.deepEqual(message.tracks[0].extentMeters, [0.03, 15, 0.03]);
  assert.equal(message.tracks[0].yawRadians, Math.PI);
});

test('preserves normalized client pose type', () => {
  const message = parseClientMessage(JSON.stringify({
    type: 'client_pose',
    pose: { position: [1, 2, 3], rotation: [0, 0, 0, 1], tracking: 'TRACKING' }
  }));
  assert.equal(message.type, 'client_pose');
  assert.deepEqual(message.pose.position, [1, 2, 3]);
});

test('rejects malformed vectors and unsupported types', () => {
  assert.throws(() => parseClientMessage('{"type":"track_batch","tracks":[{"id":"x","position":[1]}]}'), /must have 3 numbers/);
  assert.throws(() => parseClientMessage('{"type":"explode"}'), /Unsupported/);
});
