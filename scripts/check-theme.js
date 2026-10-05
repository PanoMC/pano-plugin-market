// Theme static checks of 14 §20.2. Usage: check-theme.js [--strict] [--root dir]
// Rules 1 (style= allow-list) and 3 (exactly the three {@html} files) describe the finished
// storefront; they only warn while the legacy theme files exist and fail under --strict.
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

export const STYLE_ATTR_FILES = ['CategoryNode', 'PaymentMethodPicker', 'GoalWidget'];
export const HTML_FILES = ['ProductPage', 'LegalModal', 'PaymentInstructions'];
export const SDK_IMPORTS = [
  '@panomc/sdk',
  '@panomc/sdk/utils/api',
  '@panomc/sdk/utils/language',
  '@panomc/sdk/toasts',
  '@panomc/sdk/svelte',
  '@panomc/sdk/components/theme',
  '@panomc/sdk/utils/component',
];

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
    const { markup, scripts, styles } = splitSvelte(source);
    for (const st of styles) add('1', st.index, '<style> block in src/theme');
    for (const tag of parseTags(markup)) {
      const html = /^[a-z]/.test(tag.name) && !tag.name.includes(':') && !tag.name.includes('.');
      if (/(?:^|\s)style\s*=/.test(tag.attrs) && !STYLE_ATTR_FILES.includes(baseName(file)))
        add('1', tag.index, `style= attribute outside ${STYLE_ATTR_FILES.join(' / ')}`, {
          pending: true,
        });
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
        add('3', sink.index, `{@html} outside ${HTML_FILES.join(' / ')}`, { pending: true });
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
  // 4. SDK import allow-list
  for (const m of code.matchAll(/(?:from\s+|import\s*\(\s*)['"](@panomc\/sdk[^'"]*)['"]/g))
    if (!SDK_IMPORTS.includes(m[1]))
      report.add('4', name, 0, `import of '${m[1]}' is not on the allow-list`);
  // 5b. theme.* keys referenced by literals
  for (const { key, index } of usedKeys(source))
    if (key.startsWith('theme.') && !theme.has(key))
      add('5', index, `$_('${key}') missing in src/locales/theme/en-US.json`);
}

// 5a. identical key sets
const maps = Object.fromEntries(LANGS.map((l) => [l, loadFragment(root, 'theme', l)]));
if (LANGS.some((l) => !maps[l]))
  report.add('5', 'src/locales/theme', 0, 'a locale file is missing');
else for (const p of compareLocales(maps, 'src/locales/theme')) report.add('5', '', 0, p);

report.note(`scanned ${files.length} theme files`);
process.exit(report.finish());
