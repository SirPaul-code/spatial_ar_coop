import test from 'node:test';
import assert from 'node:assert/strict';
import { opsPage } from '../src/ops-page-hifi.mjs';

test('hi-fi ops page embeds parseable 60fps free-fly and pose rendering client', () => {
  const html = opsPage();
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script, 'ops client script must exist');
  assert.doesNotThrow(() => new Function(script), 'embedded hi-fi JavaScript must parse');
  assert.match(html, /SPATIAL OPS · HI-FI/);
  assert.match(html, /Free fly 3D/);
  assert.match(html, /WASD/);
  assert.match(html, /requestAnimationFrame/);
  assert.match(html, /LIVE_POLL_MS=100/);
  assert.match(html, /INTERPOLATION_DELAY_MS/);
  assert.match(html, /poseJoints/);
  assert.match(html, /mission-replay/);
  assert.match(html, /ops-state/);
});
