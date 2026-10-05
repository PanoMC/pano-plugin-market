// `bun run check:i18n` (17 §10). Usage: check-i18n.js [--side panel|theme] [--strict] [--root dir]
import fs from 'node:fs';
import path from 'node:path';
import {
  LANGS,
  Report,
  fragmentsFor,
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
import { ACTIVITY_LOG_TYPES, PERMISSION_KEYS, pascal } from './lib/required-keys.js';

const args = parseArgs();
const root = args.root ? path.resolve(args.root) : defaultRoot;
const report = new Report(`check:i18n${args.side ? `:${args.side}` : ''}`, { strict: args.strict });
const fragments = fragmentsFor(args.side);

// 1. identical key sets / non-empty values / same placeholders, per fragment
for (const fragment of fragments) {
  const maps = Object.fromEntries(LANGS.map((l) => [l, loadFragment(root, fragment, l)]));
  if (LANGS.some((l) => !maps[l])) {
    report.add('locale-files', `src/locales/${fragment}`, 0, 'one of tr / en-US / ru is missing');
    continue;
  }
  for (const p of compareLocales(maps, `src/locales/${fragment}`)) report.add('locale', '', 0, p);
}

// 2. required permission / activity-log keys of 04 §9-§10. A key is required once its Kotlin class
//    exists (so each backend slice has to ship its texts); --strict requires all of them.
const kotlin = path.join(root, 'src/main/kotlin/com/panomc/plugins/market');
const hasClass = (dir, name) => fs.existsSync(path.join(kotlin, dir, `${name}.kt`));
const merged = Object.fromEntries(LANGS.map((l) => [l, loadMerged(root, ['core'], l)]));
let requiredChecked = 0;
let requiredSkipped = 0;
const requiredKeys = [
  ...PERMISSION_KEYS.flatMap((k) => [
    {
      cls: hasClass('permission', `${pascal(k)}Permission`),
      keys: [`permissions.${k}.title`, `permissions.${k}.description`],
    },
  ]),
  ...ACTIVITY_LOG_TYPES.map((t) => ({
    cls: hasClass('log', `${pascal(t)}Log`),
    keys: [`activity-logs.${t}`],
  })),
];
for (const { cls, keys } of requiredKeys) {
  if (!cls && !args.strict) {
    requiredSkipped++;
    continue;
  }
  requiredChecked++;
  for (const lang of LANGS)
    for (const key of keys)
      if (!merged[lang].has(key))
        report.add('required-key', `src/locales/core/${lang}.json`, 0, `missing '${key}'`);
}
report.note(
  `required permission / activity-log entries checked: ${requiredChecked}, not yet implemented in Kotlin (skipped): ${requiredSkipped}`,
);

// 3. every literal $_('key') exists in en-US (merged: the host merges all fragments)
const enUS = loadMerged(root, ['core', 'panel', 'theme'], 'en-US');
const dirs = args.side ? [`src/${args.side}`] : ['src/panel', 'src/theme'];
let used = 0;
for (const dir of dirs) {
  for (const file of walk(path.join(root, dir), ['.svelte', '.js'])) {
    if (file.endsWith('.test.js')) continue;
    const source = read(file);
    for (const { key, index } of usedKeys(source)) {
      used++;
      if (!keyResolves(key, enUS))
        report.add(
          'used-key',
          rel(file, root),
          lineOf(source, index),
          `$_('${key}') does not exist in en-US`,
        );
    }
  }
}
report.note(`$_() literals checked: ${used}`);
process.exit(report.finish());
