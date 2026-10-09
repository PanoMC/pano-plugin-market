// Static rules of 17 §10 `check:static` as pure functions over (file, source).
import { hardcodedTexts, htmlSinks, parseTags, splitSvelte, topLevelGlobals } from './scan.js';
import { lineOf } from './common.js';

function allowlistEntry(allowlist, file) {
  return allowlist.entries.find((e) =>
    e.file.includes('/') ? e.file === file : file === e.file || file.endsWith(`/${e.file}`),
  );
}

// Returns [{ rule, file, line, message }].
export function checkSvelteFile(file, source, { allowlist }) {
  const out = [];
  const add = (rule, index, message) =>
    out.push({ rule, file, line: lineOf(source, index), message });
  const { markup, scripts } = splitSvelte(source);

  for (const s of scripts) {
    for (const m of s.code.matchAll(/^\s*export\s+let\s/gm))
      add('runes', s.offset + m.index, '`export let` (use $props())');
    for (const m of s.code.matchAll(/^\s*\$:\s/gm))
      add('runes', s.offset + m.index, '`$:` reactive statement (use $derived/$effect)');
    for (const m of s.code.matchAll(/\bcreateEventDispatcher\b/g))
      add('runes', s.offset + m.index, 'createEventDispatcher (use callback props)');
    if (s.module)
      for (const h of topLevelGlobals(s.code, ['localStorage', 'sessionStorage']))
        add('storage', s.offset + h.index, `${h.name} read at module top level`);
  }

  for (const tag of parseTags(markup)) {
    const html = /^[a-z]/.test(tag.name) && !tag.name.includes(':') && !tag.name.includes('.');
    if (html && /(?:^|\s)on:[\w-]+/.test(tag.attrs))
      add('runes', tag.index, `<${tag.name}> uses an on: directive (use onclick={...})`);
    if (tag.name === 'slot') add('runes', tag.index, '<slot> (use snippets)');
    if (tag.name === 'svelte:self') add('runes', tag.index, '<svelte:self> (import the component)');
  }

  const sinks = htmlSinks(markup);
  if (sinks.length) {
    const entry = allowlistEntry(allowlist, file);
    for (const sink of sinks) {
      if (!entry) add('html', sink.index, `{@html} outside scripts/html-allowlist.json`);
      else if (entry.wrapper && !sink.expr.startsWith(`${entry.wrapper}(`))
        add('html', sink.index, `{@html ${sink.expr}} must be wrapped in ${entry.wrapper}()`);
    }
  }

  const allowedTexts = new Set(allowlist.allowedTexts ?? []);
  for (const t of hardcodedTexts(markup)) {
    const words = t.text.split(/[^\p{L}]+/u).filter(Boolean);
    if (words.every((w) => allowedTexts.has(w))) continue;
    add('text', t.index, `hard-coded text "${t.text}"`);
  }
  return out;
}

export function checkJsFile(file, source) {
  const out = [];
  for (const h of topLevelGlobals(source, ['localStorage', 'sessionStorage']))
    out.push({
      rule: 'storage',
      file,
      line: lineOf(source, h.index),
      message: `${h.name} read at module top level`,
    });
  for (const m of source.matchAll(/\bcreateEventDispatcher\b/g))
    out.push({
      rule: 'runes',
      file,
      line: lineOf(source, m.index),
      message: 'createEventDispatcher',
    });
  return out;
}
