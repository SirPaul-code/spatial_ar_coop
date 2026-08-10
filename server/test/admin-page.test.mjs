import test from 'node:test';
import assert from 'node:assert/strict';
import { adminPage } from '../src/admin-page.mjs';

test('operator dashboard embeds valid JavaScript and spatial debugger affordances', () => {
  const html = adminPage();
  const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
  assert.ok(script, 'dashboard script must exist');
  assert.doesNotThrow(() => new Function(script), 'embedded dashboard JavaScript must parse');
  assert.match(html, /point-cloud/);
  assert.match(html, /live-state/);
  assert.match(html, /Top-down/);
  assert.match(html, />Anchors</);
  assert.match(html, /Last track/);
  assert.match(html, /featureQuality/);
  assert.match(html, /lastError/);
  assert.match(html, /server cannot independently know an ARCore anchor's physical world pose/i);
  assert.doesNotMatch(html, /#75e7b0|#07110e|#101f1b/i, 'old green-on-black palette must not return');
});
