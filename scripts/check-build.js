// `bun run check:build` (17 §10) and the compile step of check:panel / check:theme (D-WB2).
//   check-build.js                 verify src/main/resources/plugin-ui (run after `bun run build`)
//   check-build.js --side panel    compile that side into build/ui-check/panel/ with rollup, then verify it
// A side build never touches src/main/resources/plugin-ui, so the two lanes do not clobber each other.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { Report, parseArgs, root, walk, rel } from './lib/common.js';

const args = parseArgs();
const report = new Report(`check:build${args.side ? `:${args.side}` : ''}`, {
  strict: args.strict,
});

let outDir = path.join(root, 'src/main/resources/plugin-ui');
if (args.side) {
  outDir = path.join(root, 'build/ui-check', args.side);
  const rollup = path.join(root, 'node_modules/.bin/rollup');
  const run = spawnSync(rollup, ['-c'], {
    cwd: root,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, MARKET_UI_SIDE: args.side, DEV: 'false' },
    encoding: 'utf8',
  });
  if (run.status !== 0) {
    process.stderr.write(run.stdout + run.stderr);
    report.add('build', `rollup (${args.side})`, 0, `exited with ${run.status}`);
    process.exit(report.finish());
  }
}

for (const entry of ['client/client.mjs', 'server/server.mjs']) {
  const file = path.join(outDir, entry);
  if (!fs.existsSync(file)) report.add('entry', rel(file), 0, 'missing');
  else if (fs.statSync(file).size === 0) report.add('entry', rel(file), 0, 'empty');
}

// Bare specifiers the host import map provides to the client bundle (rollup.config.js `external`).
const hostProvided = (id) =>
  id === 'svelte' ||
  id.startsWith('svelte/') ||
  id === 'svelte-i18n' ||
  id === '@panomc/sdk' ||
  id.startsWith('@panomc/sdk/');

// "Duplicated runtime": the Svelte runtime must exist at most once. The client bundle keeps it
// external (host import map), so no client file may contain an inlined runtime copy; the server
// bundle inlines it once (one file). An inlined copy is recognised by Svelte's error-url marker,
// which survives minification; plain `svelte/internal/*` import specifiers are not copies.
const RUNTIME_MARKER = 'https://svelte.dev/e/';
for (const bundle of ['client', 'server']) {
  const files = walk(path.join(outDir, bundle), ['.mjs', '.js']);
  const copies = [];
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8');
    if (text.includes(RUNTIME_MARKER)) copies.push(rel(file));
    if (bundle !== 'client') continue;
    const re = /(?:\bfrom\s*|\bimport\s*\(\s*|^\s*import\s*)["']([^"'./][^"']*)["']/gm;
    for (const m of text.matchAll(re))
      if (!hostProvided(m[1]))
        report.add(
          'import',
          rel(file),
          0,
          `client imports '${m[1]}' which the host import map does not provide`,
        );
  }
  const allowed = bundle === 'client' ? 0 : 1;
  if (copies.length > allowed)
    report.add(
      'runtime',
      `${bundle}/`,
      0,
      `svelte runtime inlined in ${copies.length} files (${copies.join(', ')}), allowed ${allowed}`,
    );
}

report.note(`verified ${rel(outDir)}`);
process.exit(report.finish());
