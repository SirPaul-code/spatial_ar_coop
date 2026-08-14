import test from 'node:test';
import assert from 'node:assert/strict';
import { opsPage } from '../src/ops-page.mjs';

test('ops console embeds a parseable COP, fusion provenance and mission replay UI', () => {
  const html = opsPage();
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script);
  assert.doesNotThrow(() => new Function(script));
  assert.match(html, /COMMON OPERATING PICTURE/);
  assert.match(html, /ops-state/);
  assert.match(html, /mission-replay/);
  assert.match(html, /Fused entities/);
  assert.match(html, /PROVENANCE/);
  assert.match(html, /Multi-sensor confirmed/);
  assert.match(html, /MISSION REPLAY/);
  assert.match(html, /Scan cloud/);
});
