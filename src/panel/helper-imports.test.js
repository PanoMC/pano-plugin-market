import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';

// The panel browser scenarios (E2E-14) found /market/payment-events answering a ReferenceError (`isUnverified is not defined`) as soon as the list
// held one event: the page called a helper of utils/payment-events.js it never imported, and nothing checked templates for that. Every call of a
// helper exported by a panel utility must be backed by an import (or a local declaration) in the same .svelte file.
function* files(dir, pattern) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* files(full, pattern);
    else if (pattern.test(entry.name) && !/\.test\.js$/.test(entry.name)) yield full;
  }
}

const utilsDir = path.join(import.meta.dir, 'utils');

/** name -> the util files exporting it */
function exportedHelpers() {
  const byName = new Map();

  for (const file of files(utilsDir, /\.js$/)) {
    const source = fs.readFileSync(file, 'utf8');
    const names = [
      ...source.matchAll(/^export\s+(?:async\s+)?(?:const|function)\s+([A-Za-z_$][\w$]*)/gm),
    ].map((m) => m[1]);
    for (const name of names) byName.set(name, [...(byName.get(name) ?? []), path.basename(file)]);
  }

  return byName;
}

const stripNoise = (source) =>
  source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');

/** Names a .svelte source declares itself: functions, variables and the props of `$props()`. */
function declaredNames(source) {
  const names = new Set(
    [...source.matchAll(/\b(?:function|const|let|var)\s+([\w$]+)/g)].map((m) => m[1]),
  );

  for (const m of source.matchAll(/\{([\s\S]*?)\}\s*=\s*\$props\(\)/g))
    for (const part of m[1].split(',')) {
      const name = part
        .trim()
        .split(/[\s=:]/)[0]
        .replace(/^\.\.\./, '');
      if (name) names.add(name);
    }

  return names;
}

/** Names a source imports (default, named, `as`). */
function importedNames(source) {
  const imported = new Set();

  for (const m of source.matchAll(/import\s+([\s\S]*?)\s+from\s+['"][^'"]+['"]/g))
    for (const chunk of m[1].replace(/[{}]/g, ' ').split(',')) {
      const bare = chunk
        .trim()
        .split(/\s+as\s+/)
        .pop()
        .trim();
      if (bare) imported.add(bare);
    }

  return imported;
}

describe('panel templates use only helpers they import', () => {
  test('every call of a utils helper has an import or a local declaration', () => {
    const helpers = exportedHelpers();
    const offenders = [];

    for (const file of files(path.join(import.meta.dir), /\.svelte$/)) {
      const source = stripNoise(fs.readFileSync(file, 'utf8'));
      const imported = importedNames(source);
      const declared = declaredNames(source);
      // a call `name(`, not a method (`.name(`)
      const called = new Set(
        [...source.matchAll(/(?<![.\w$])([A-Za-z_$][\w$]*)\(/g)].map((m) => m[1]),
      );

      for (const name of called)
        if (helpers.has(name) && !imported.has(name) && !declared.has(name))
          offenders.push(`${path.relative(import.meta.dir, file)}: ${name}`);
    }

    expect(offenders).toEqual([]);
  });

  test('the check sees a missing import (it would have caught isUnverified)', () => {
    const source =
      "{#if isUnverified(event)}x{/if}<script>import { eventTypesText } from '../utils/payment-events.js';</script>";

    expect(importedNames(source).has('isUnverified')).toBe(false);
    expect(importedNames(source).has('eventTypesText')).toBe(true);
    expect(declaredNames(source).has('isUnverified')).toBe(false);
  });
});
