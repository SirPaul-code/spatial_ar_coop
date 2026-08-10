import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createSpatialServer } from '../src/app.mjs';

async function createMap(base, token, id) {
  const response = await fetch(`${base}/api/v1/maps`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, name: id, createdBy: 'security-test' })
  });
  assert.equal(response.status, 201);
  return response.json();
}

test('server identity is stable and map keys isolate map access', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'spatial-security-'));
  const adminToken = 'admin-secret-for-tests';
  let firstServerId;
  const app = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, adminToken, stdout: false });
  try {
    const address = await app.start();
    const base = `http://127.0.0.1:${address.port}`;

    const info = await fetch(`${base}/api/v1/info`);
    assert.equal(info.status, 200);
    const serverInfo = await info.json();
    firstServerId = serverInfo.serverId;
    assert.match(firstServerId, /^srv_/);

    const deniedList = await fetch(`${base}/api/v1/maps`);
    assert.equal(deniedList.status, 401);

    const alpha = await createMap(base, adminToken, 'alpha');
    const beta = await createMap(base, adminToken, 'beta');
    assert.match(alpha.accessKey, /^sar_map_/);
    assert.match(beta.accessKey, /^sar_map_/);
    assert.notEqual(alpha.accessKey, beta.accessKey);

    const alphaRead = await fetch(`${base}/api/v1/maps/alpha`, {
      headers: { Authorization: `Bearer ${alpha.accessKey}` }
    });
    assert.equal(alphaRead.status, 200);
    assert.equal((await alphaRead.json()).id, 'alpha');

    const crossRead = await fetch(`${base}/api/v1/maps/beta`, {
      headers: { Authorization: `Bearer ${alpha.accessKey}` }
    });
    assert.equal(crossRead.status, 404);

    const invite = await fetch(`${base}/api/v1/maps/alpha/invite`, {
      headers: { Authorization: `Bearer ${alpha.accessKey}` }
    });
    assert.equal(invite.status, 200);
    const inviteBody = await invite.json();
    assert.equal(inviteBody.invite.mapId, 'alpha');
    assert.equal(inviteBody.invite.mapKey, alpha.accessKey);
    assert.match(inviteBody.invite.deepLink, /^spatialar:\/\/join\?/);

    const deniedSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?mapId=beta&clientId=bad&role=viewer&token=${encodeURIComponent(alpha.accessKey)}`);
    const rejected = await new Promise((resolve) => {
      const timer = setTimeout(() => resolve(false), 2500);
      deniedSocket.addEventListener('open', () => { clearTimeout(timer); resolve(false); }, { once: true });
      deniedSocket.addEventListener('error', () => { clearTimeout(timer); resolve(true); }, { once: true });
      deniedSocket.addEventListener('close', () => { clearTimeout(timer); resolve(true); }, { once: true });
    });
    assert.equal(rejected, true);
  } finally {
    await app.stop();
  }

  const restarted = createSpatialServer({ host: '127.0.0.1', port: 0, dataDir, adminToken, stdout: false });
  try {
    const address = await restarted.start();
    const info = await fetch(`http://127.0.0.1:${address.port}/api/v1/info`);
    assert.equal((await info.json()).serverId, firstServerId);
  } finally {
    await restarted.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
