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

  const legacy = parseClientMessage(JSON.stringify({
    type: 'track_batch', tracks: [{ id: 't1', label: 'bird', position: [0, 0, 0] }]
  }));
  assert.equal(legacy.replaceSource, false);
  assert.deepEqual(legacy.tracks[0].extentMeters, [0.45, 0.45, 0.55]);
  assert.equal(legacy.tracks[0].yawRadians, 0);
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
