import test from 'node:test';
import assert from 'node:assert/strict';
import { adminPage } from '../src/admin-page.mjs';

test('world debugger embeds valid JavaScript, owner auth and live spatial layers', () => {
  const html = adminPage();
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script, 'dashboard script must exist');
  assert.doesNotThrow(() => new Function(script), 'embedded dashboard JavaScript must parse');

  assert.match(html, /Server owner access/);
  assert.match(html, /sar_admin_/);
  assert.match(html, /place invite key/i);
  assert.match(html, /Owner access/);
  assert.match(html, /sessionStorage/);

  assert.match(html, /point-cloud/);
  assert.match(html, /live-state/);
  assert.match(html, /Top-down/);
  assert.match(html, /Cloud anchors/);
  assert.match(html, /Participants/);
  assert.match(html, /Live objects/);
  assert.match(html, /Terrain/);
  assert.match(html, /Client trails/);
  assert.match(html, /Track trails/);
  assert.match(html, /Velocity/);
  assert.match(html, /Uncertainty/);
  assert.match(html, /featureQuality/);
  assert.match(html, /lastError|Cloud Anchor service/);
  assert.match(html, /internal visual feature map/i);
  assert.doesNotMatch(html, /#75e7b0|#07110e|#101f1b/i, 'old green-on-black palette must not return');
});
