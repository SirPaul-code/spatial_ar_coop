import test from 'node:test';
import assert from 'node:assert/strict';
import { parseClientMessage, ProtocolError, validateClientIdentity } from '../src/protocol.mjs';

test('validates client identity', () => {
  assert.deepEqual(validateClientIdentity({ mapId: 'yard-1', clientId: 'phone_a', role: 'sensor' }), { mapId: 'yard-1', clientId: 'phone_a', role: 'sensor' });
  assert.throws(() => validateClientIdentity({ mapId: '../etc', clientId: 'x', role: 'viewer' }), ProtocolError);
});

test('normalizes track batches', () => {
  const message = parseClientMessage(JSON.stringify({
    type: 'track_batch', sequence: 7, tracks: [{ id: 't1', label: 'Person', confidence: 2, position: [1, 2, 3] }]
  }));
  assert.equal(message.sequence, 7);
  assert.equal(message.tracks[0].label, 'person');
  assert.equal(message.tracks[0].confidence, 1);
  assert.deepEqual(message.tracks[0].velocity, [0, 0, 0]);
});

test('rejects malformed vectors and unsupported types', () => {
  assert.throws(() => parseClientMessage('{"type":"track_batch","tracks":[{"id":"x","position":[1]}]}'), /must have 3 numbers/);
  assert.throws(() => parseClientMessage('{"type":"explode"}'), /Unsupported/);
});
