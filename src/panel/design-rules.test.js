import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';

// Scenario 73 of 13 section 25.4 (E2E-15) found the search input of the Categories, Comparisons and Gift Codes lists unfocused after load: the
// SearchInput host component only takes the focus when it gets `autofocus` (13 section 1.7: the CardHeader search input has `autofocus`).
function* svelteFiles(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* svelteFiles(full);
    else if (entry.name.endsWith('.svelte')) yield full;
  }
}

/** Every `<SearchInput ... />` start tag of a file (up to the closing `/>`). */
const searchTags = (source) => [...source.matchAll(/<SearchInput\b[\s\S]*?\/>/g)].map((m) => m[0]);

describe('list pages follow the design rules', () => {
  test('every list page and list section puts autofocus on its CardHeader search input', () => {
    const offenders = [];
    let seen = 0;

    for (const dir of ['pages', 'components/discounts']) {
      for (const file of svelteFiles(path.join(import.meta.dir, dir))) {
        for (const tag of searchTags(fs.readFileSync(file, 'utf8'))) {
          seen++;
          if (!/\bautofocus\b/.test(tag)) offenders.push(path.relative(import.meta.dir, file));
        }
      }
    }

    expect(seen).toBeGreaterThanOrEqual(13); // a positive control: the scan really found the search inputs
    expect(offenders).toEqual([]);
  });
});
