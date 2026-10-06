import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';

// The panel browser scenarios (E2E-14) found /market/orders/create-order answering 500 on a direct visit: the page called Svelte's
// `onDestroy`, which the plugin's own server bundle cannot run during SSR ("null is not an object (evaluating 'o.r')"). Cleanup of a
// panel component goes into the return value of `$effect` / `onMount`, which never runs on the server.
function* files(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* files(full);
    else if (/\.(svelte|js)$/.test(entry.name) && !/\.test\.js$/.test(entry.name)) yield full;
  }
}

describe('panel components render on the server', () => {
  test('no panel file imports or calls onDestroy', () => {
    const offenders = [];

    for (const file of files(import.meta.dir)) {
      const source = fs.readFileSync(file, 'utf8');
      source.split('\n').forEach((line, index) => {
        if (/^\s*\/\//.test(line)) return;
        if (/\bonDestroy\b/.test(line))
          offenders.push(`${path.relative(import.meta.dir, file)}:${index + 1}`);
      });
    }

    expect(offenders).toEqual([]);
  });
});

describe('the host rich text editor', () => {
  test('is only mounted through ClientEditor (it needs window while it is created)', () => {
    const offenders = [];

    for (const file of files(import.meta.dir)) {
      if (file.endsWith('ClientEditor.svelte')) continue;
      const source = fs.readFileSync(file, 'utf8');
      source.split('\n').forEach((line, index) => {
        // `Editor,` inside an import list of '@panomc/sdk/components/panel', or `<Editor ` in a template
        if (/^\s*Editor,?\s*$/.test(line) || /<Editor[\s>/]/.test(line))
          offenders.push(`${path.relative(import.meta.dir, file)}:${index + 1}`);
      });
    }

    expect(offenders).toEqual([]);
  });

  test('ClientEditor renders the editor after mount only', () => {
    const source = fs.readFileSync(
      path.join(import.meta.dir, 'components/ClientEditor.svelte'),
      'utf8',
    );

    expect(source).toMatch(/onMount\(\(\) => \{\s*mounted = true;/);
    expect(source).toMatch(/\{#if mounted\}\s*<Editor/);
  });
});
