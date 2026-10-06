import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';

// The browser smoke (E2E-13) showed "€6.00 / {period}" and a raw "{count, plural, ...}" on the storefront: the plugin's `_` passes its
// second argument to svelte-i18n unchanged, which reads placeholders from `values`, so `$_('key', { period })` renders the template
// unfilled. Every theme call with an argument object must therefore use the `{ values: {...} }` (or another option) form.
function* files(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== '__tests__') yield* files(full);
    } else if (/\.(svelte|js)$/.test(entry.name) && !/\.test\.js$/.test(entry.name)) {
      yield full;
    }
  }
}

describe('theme translations', () => {
  test('no $_ call passes placeholder values without the `values` key', () => {
    const root = path.resolve(import.meta.dir, '../..');
    const call = /\$_\(\s*(?:'[^']+'|`[^`]+`)\s*,\s*\{(?!\s*(?:values|default|locale|format)\b)/g;
    const offenders = [];

    for (const file of files(root)) {
      const source = fs.readFileSync(file, 'utf8');
      for (const match of source.matchAll(call)) {
        offenders.push(
          `${path.relative(root, file)}:${source.slice(0, match.index).split('\n').length}`,
        );
      }
    }

    expect(offenders).toEqual([]);
  });
});
