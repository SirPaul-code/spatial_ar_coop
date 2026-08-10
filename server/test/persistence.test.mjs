import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { MapStore, decodeScanChunk } from '../src/persistence.mjs';

function logger() { return { info() {}, warn() {}, error() {}, debug() {} }; }
function tempDir() { return fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-store-')); }
function scanChunk(points = [[1, 2, 3, .8], [4, 5, 6, .9]]) {
  const raw = Buffer.alloc(16 + points.length * 16);
  raw.write('SAC1', 0, 4, 'ascii'); raw.writeUInt32LE(points.length, 4); raw.writeBigInt64LE(1234n, 8);
  points.flat().forEach((value, index) => raw.writeFloatLE(value, 16 + index * 4));
  return zlib.gzipSync(raw);
}

test('persists map, anchor, ground and scan metadata', () => {
  const dir = tempDir();
  try {
    const store = new MapStore({ dataDir: dir, logger: logger() });
    store.createMap({ id: 'yard', name: 'Back yard', createdBy: 'phone' });
    store.patchMap('yard', { groundY: -0.25, status: 'READY' });
    store.upsertAnchor('yard', { id: 'a1', cloudAnchorId: 'cloud', status: 'HOSTED', featureQuality: 'GOOD', siteFromAnchor: [1,0,0,0,0,1,0,0,0,0,1,0,2,0,3,1] });
    const first = store.storeScanChunk('yard', { chunkId: 'c1', deviceId: 'phone', body: scanChunk() });
    const duplicate = store.storeScanChunk('yard', { chunkId: 'c1', deviceId: 'phone', body: scanChunk() });
    assert.equal(first.pointCount, 2);
    assert.equal(duplicate.duplicate, true);
    const map = store.getMap('yard');
    assert.equal(map.groundY, -0.25);
    assert.equal(map.anchors.length, 1);
    assert.equal(map.scan.chunkCount, 1);
    assert.equal(map.scan.pointCount, 2);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('validates SAC1 chunk integrity', () => {
  assert.equal(decodeScanChunk(scanChunk()).pointCount, 2);
  assert.throws(() => decodeScanChunk(Buffer.from('bad')), /gzip/);
});
