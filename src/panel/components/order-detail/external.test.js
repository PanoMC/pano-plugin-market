import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { ACTION_DEFS } from './actions.js';

// external.js imports .svelte files, which bun test cannot load: it is read as text. The panel
// build check compiles the imports for real.
const source = fs.readFileSync(path.join(import.meta.dir, 'external.js'), 'utf8');
const entries = Object.fromEntries(
  [...source.matchAll(/^\s+(\w+):\s*(\w+),?$/gm)].map((m) => [m[1], m[2]]),
);
const imports = Object.fromEntries(
  [...source.matchAll(/^import (\w+) from '([^']+\.svelte)';$/gm)].map((m) => [m[1], m[2]]),
);

describe('order detail external modals', () => {
  const externalIds = ACTION_DEFS.filter((d) => d.kind === 'external').map((d) => d.id);

  test('every external action has a modal wired (no null, no dead menu item)', () => {
    expect(externalIds.sort()).toEqual(['createShipment', 'editShippingAddress', 'refund']);
    for (const id of externalIds) {
      expect(entries[id]).toBeDefined();
      expect(entries[id]).not.toBe('null');
      expect(imports[entries[id]]).toBeDefined();
    }
  });

  test('no entry beyond the external actions', () => {
    expect(Object.keys(entries).sort()).toEqual([...externalIds].sort());
  });

  test('each import points at an existing component', () => {
    for (const file of Object.values(imports))
      expect(fs.existsSync(path.join(import.meta.dir, file))).toBe(true);
  });
});
