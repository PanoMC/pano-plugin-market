// Market-specific theme checks (14 §20.2). Usage: check-theme.js [--strict] [--root dir]
// What every plugin must obey (the import rules for views, helpers and controllers, the two-Svelte-copies rule) is
// enforced by the build of @panomc/plugin-kit (V1-V5); only the rules of this plugin's own theme code stay here:
//   1 style= allow-list and no <style> block (the kit's style lint at badge level, the three STYLE_ATTR_FILES), 2 Svelte 5 syntax, 3 exactly the three {@html} files, 5 theme.* text keys,
//   6 no browser global at module top level, 7 the API paths of the plugin (relative to /api/plugins/<id>).
// A violation fails the run, with or without --strict.
import path from 'node:path';
import {
  LANGS,
  Report,
  loadFragment,
  lineOf,
  parseArgs,
  read,
  rel,
  root as defaultRoot,
  walk,
} from './lib/common.js';
import { compareLocales, usedKeys } from './lib/i18n-rules.js';
import { htmlSinks, parseTags, splitSvelte, topLevelGlobals } from './lib/scan.js';
import { STYLE_ATTR_FILES, STYLE_RULES, lintStyles } from './lib/styles.js';

export { STYLE_ATTR_FILES };
export const HTML_FILES = ['ProductPage', 'LegalModal', 'PaymentInstructions'];
// The site API of the market, as markup and links write it.
// lib/paths.js owns the constant and is the one file that spells the prefix out in pieces.
const PATHS_FILE = 'src/theme/lib/paths.js';
export const SITE_API_ROOT = '/api/plugins/pano-plugin-market';

const args = parseArgs();
const root = args.root ? path.resolve(args.root) : defaultRoot;
const report = new Report('check:theme', { strict: args.strict });
const baseName = (file) => path.basename(file).replace(/\.svelte$/, '');

const files = walk(path.join(root, 'src/theme'), ['.svelte', '.js']).filter(
  (f) => !f.endsWith('.test.js'),
);
const theme = loadFragment(root, 'theme', 'en-US') ?? new Map();
const htmlSeen = new Set();

for (const file of files) {
  const name = rel(file, root);
  const source = read(file);
  const add = (rule, index, message, opts) =>
    report.add(rule, name, lineOf(source, index), message, opts);
  let code = source;
  if (file.endsWith('.svelte')) {
    const { markup, scripts } = splitSvelte(source);
    for (const tag of parseTags(markup)) {
      const html = /^[a-z]/.test(tag.name) && !tag.name.includes(':') && !tag.name.includes('.');
      if (html && /(?:^|\s)on:[\w-]+/.test(tag.attrs))
        add('2', tag.index, `on: directive on <${tag.name}>`);
      if (tag.name === 'slot') add('2', tag.index, '<slot>');
      if (tag.name === 'svelte:self') add('2', tag.index, '<svelte:self>');
    }
    for (const s of scripts) {
      for (const m of s.code.matchAll(/^\s*export\s+let\s/gm))
        add('2', s.offset + m.index, 'export let');
      for (const m of s.code.matchAll(/^\s*\$:\s/gm)) add('2', s.offset + m.index, '$: statement');
      for (const m of s.code.matchAll(/\bcreateEventDispatcher\b/g))
        add('2', s.offset + m.index, 'createEventDispatcher');
      if (s.module)
        for (const h of topLevelGlobals(s.code, [
          'window',
          'document',
          'localStorage',
          'sessionStorage',
        ]))
          add('6', s.offset + h.index, `${h.name} at module top level`);
    }
    for (const sink of htmlSinks(markup)) {
      htmlSeen.add(baseName(file));
      if (!HTML_FILES.includes(baseName(file)))
        add('3', sink.index, `{@html} outside ${HTML_FILES.join(' / ')}`);
    }
    code = scripts.map((s) => s.code).join('\n');
  } else {
    for (const h of topLevelGlobals(source, [
      'window',
      'document',
      'localStorage',
      'sessionStorage',
    ]))
      add('6', h.index, `${h.name} at module top level`);
  }
  // 7. API paths: calls take paths relative to the plugin ('/store/products'), links and images use the full
  // /api/plugins/pano-plugin-market/... form; any other '/api/' literal is a leftover of the old API
  if (name !== PATHS_FILE)
    for (const m of source.matchAll(
      /(['"`}])(\/api\/(?!plugins\/pano-plugin-market\/)[^'"`\s]*)/g,
    )) {
      const lineStart = source.lastIndexOf('\n', m.index) + 1;
      if (/^\s*(\/\/|\*|\/\*)/.test(source.slice(lineStart, m.index + 1))) continue;
      add(
        '7',
        m.index,
        `'${m[2]}' is not a market API path (calls: '/store/products'; links: ${SITE_API_ROOT}/...)`,
      );
    }
  // 5b. theme.* keys referenced by literals
  for (const { key, index } of usedKeys(source))
    if (key.startsWith('theme.') && !theme.has(key))
      add('5', index, `$_('${key}') missing in src/locales/theme/en-US.json`);
}

// 1. style= and <style>: the kit's style lint at badge level (style-block-scope, style-attr; the class rules are check-static's)
for (const f of await lintStyles(root))
  if (STYLE_RULES.includes(f.rule))
    report.add('1', f.file, f.line, `[${f.rule}] ${f.message}`, { pending: f.level === 'warn' });

// 3b. "exactly" the three files: each allow-listed file that exists must really use {@html}
for (const file of files) {
  const base = baseName(file);
  if (file.endsWith('.svelte') && HTML_FILES.includes(base) && !htmlSeen.has(base))
    report.add('3', rel(file, root), 0, `${base} is allow-listed for {@html} but has none`);
}

// 5a. identical key sets
const maps = Object.fromEntries(LANGS.map((l) => [l, loadFragment(root, 'theme', l)]));
if (LANGS.some((l) => !maps[l]))
  report.add('5', 'src/locales/theme', 0, 'a locale file is missing');
else for (const p of compareLocales(maps, 'src/locales/theme')) report.add('5', '', 0, p);

report.note(`scanned ${files.length} theme files`);
process.exit(report.finish());
