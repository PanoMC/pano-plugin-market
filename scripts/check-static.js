// `bun run check:static` (17 §10): runes rule, {@html} allow-list, hard-coded text, localStorage at module top level, and
// on the theme side the class rules of the kit's style lint at badge level (allowed classes, root and part classes, no
// --bs-* variables; the `style=` / `<style>` rules are check-theme's). Usage: check-static.js [--side panel|theme] [--root dir]
import path from 'node:path';
import fs from 'node:fs';
import { Report, parseArgs, read, rel, root as defaultRoot, walk } from './lib/common.js';
import { checkJsFile, checkSvelteFile } from './lib/static-rules.js';
import { STYLE_RULES, lintStyles } from './lib/styles.js';

const args = parseArgs();
const root = args.root ? path.resolve(args.root) : defaultRoot;
const report = new Report(`check:static${args.side ? `:${args.side}` : ''}`, {
  strict: args.strict,
});
const allowlist = JSON.parse(read(path.join(defaultRoot, 'scripts/html-allowlist.json')));

const dirs = args.side ? [`src/${args.side}`] : ['src/panel', 'src/theme'];
let svelteCount = 0;
let jsCount = 0;
for (const dir of dirs) {
  for (const file of walk(path.join(root, dir), ['.svelte', '.js'])) {
    if (file.endsWith('.test.js')) continue;
    const name = rel(file, root);
    const found = file.endsWith('.svelte')
      ? (svelteCount++, checkSvelteFile(name, read(file), { allowlist }))
      : (jsCount++, checkJsFile(name, read(file)));
    for (const v of found) report.add(v.rule, v.file, v.line, v.message);
  }
}
if (dirs.includes('src/theme'))
  for (const f of await lintStyles(root))
    if (!STYLE_RULES.includes(f.rule))
      report.add(f.rule, f.file, f.line, f.message, { pending: f.level === 'warn' });
report.note(`scanned ${svelteCount} .svelte and ${jsCount} .js files`);
process.exit(report.finish());
