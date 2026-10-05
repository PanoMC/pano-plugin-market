// Panel locale and source rules of 13 §25.2 (tests 45-50) and §25.3 (tests 51-55).
// Usage: node scripts/check-locales.mjs [--strict] [--root dir]
// Rules marked PENDING describe the finished panel (13): they only warn while the old code that
// still violates them exists, and fail under --strict / MARKET_STRICT=1 (the E2E / release gate).
import fs from 'node:fs';
import path from 'node:path';
import {
  LANGS,
  Report,
  loadFragment,
  loadMerged,
  lineOf,
  parseArgs,
  read,
  rel,
  root as defaultRoot,
  walk,
} from './lib/common.js';
import { compareLocales, keyResolves, usedKeys } from './lib/i18n-rules.js';
import { checkSvelteFile } from './lib/static-rules.js';

const args = parseArgs();
const root = args.root ? path.resolve(args.root) : defaultRoot;
const report = new Report('check:locales', { strict: args.strict });
const allowlist = JSON.parse(read(path.join(defaultRoot, 'scripts/html-allowlist.json')));

// 45. identical key sets (panel fragment)
const maps = Object.fromEntries(LANGS.map((l) => [l, loadFragment(root, 'panel', l)]));
if (LANGS.some((l) => !maps[l]))
  report.add('45', 'src/locales/panel', 0, 'a locale file is missing');
else for (const p of compareLocales(maps, 'src/locales/panel')) report.add('45', '', 0, p);

const panelFiles = walk(path.join(root, 'src/panel'), ['.svelte', '.js']).filter(
  (f) => !f.endsWith('.test.js'),
);
const sources = new Map(panelFiles.map((f) => [f, read(f)]));
const enUS = loadMerged(root, ['core', 'panel', 'theme'], 'en-US');
const panelEn = maps['en-US'] ?? new Map();

// 46. errors.<CODE> for every code of KNOWN_ERRORS
let known = null;
for (const [file, src] of sources) {
  const m = /export\s+const\s+KNOWN_ERRORS\s*=\s*(?:new\s+Set\(\s*)?\[([\s\S]*?)\]/.exec(src);
  if (m) known = { file, codes: [...m[1].matchAll(/['"]([A-Z0-9_]+)['"]/g)].map((x) => x[1]) };
}
if (!known) {
  if (args.strict) report.add('46', 'src/panel', 0, 'KNOWN_ERRORS not found (MPU-01 creates it)');
  else report.note('46 skipped: KNOWN_ERRORS does not exist yet (MPU-01)');
} else {
  for (const code of known.codes)
    for (const lang of LANGS)
      if (!maps[lang]?.has(`errors.${code}`))
        report.add('46', rel(known.file, root), 0, `errors.${code} missing in ${lang}`);
  report.note(`46 checked ${known.codes.length} error codes`);
}

// 47. enum values rendered by StatusBadge need enums.<kind>.<VALUE> (literal references only here;
//     the dynamic enums.<kind>.<value> lookups are covered by StatusBadge's own unit test)
const badge = [...sources].find(([f]) => f.endsWith('/StatusBadge.svelte'));
if (!badge) report.note('47 skipped: StatusBadge.svelte does not exist yet (MPU-01)');
else
  for (const m of badge[1].matchAll(/enums\.([a-z-]+)\.([A-Z0-9_]+)/g))
    for (const lang of LANGS)
      if (!maps[lang]?.has(`enums.${m[1]}.${m[2]}`))
        report.add('47', rel(badge[0], root), 0, `enums.${m[1]}.${m[2]} missing in ${lang}`);

// 48 (permissions / activity logs) is enforced by check:i18n.

// 49. PENDING: no key under settings.payments.methods|fields|options remains
for (const k of panelEn.keys())
  if (/^settings\.payments\.(methods|fields|options)(\.|$)/.test(k)) {
    report.add('49', 'src/locales/panel/en-US.json', 0, `obsolete key '${k}'`, { pending: true });
    break;
  }

// 50. every $_('...') literal in src/panel resolves
for (const [file, src] of sources)
  for (const { key, index } of usedKeys(src))
    if (!keyResolves(key, enUS))
      report.add('50', rel(file, root), lineOf(src, index), `$_('${key}') missing in en-US`);

// 51-53 PENDING, 54-55 hard
const forbidden = [
  ['51', /window\.confirm\(/g, 'window.confirm('],
  ['52', /data-bs-html/g, 'data-bs-html'],
  ['53', /tr-TR|minotar\.net|google\.com\/s2\/favicons|'₺'/g, 'tr-TR / minotar.net / favicons / ₺'],
];
const srcAll = walk(path.join(root, 'src'), ['.svelte', '.js']).filter(
  (f) => !f.endsWith('.test.js'),
);
for (const file of srcAll) {
  const src = read(file);
  const isPanel = rel(file, root).startsWith('src/panel/');
  for (const [id, re, label] of forbidden) {
    if (id !== '53' && !isPanel) continue;
    for (const m of src.matchAll(re))
      report.add(id, rel(file, root), lineOf(src, m.index), `forbidden ${label}`, {
        pending: true,
      });
  }
}
for (const [file, src] of sources) {
  if (!file.endsWith('.svelte')) continue;
  for (const v of checkSvelteFile(rel(file, root), src, { allowlist, isTheme: false }))
    if (v.rule === 'runes') report.add('54', v.file, v.line, v.message);
    else if (v.rule === 'html') report.add('55', v.file, v.line, v.message);
}
report.note(`scanned ${sources.size} panel files`);
process.exit(report.finish());
