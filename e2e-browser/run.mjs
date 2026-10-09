// Browser runner (17 section 10, T6). `bun run e2e:browser [-- <filter>...]` runs every scenario file under e2e-browser/ whose id or path
// contains one of the filters (`smoke/`, `panel/`, `theme/`, `UI-03`); no filter = all. The instance comes from MARKET_E2E_* (e2e-instance.sh
// start; `--ui external:<theme>,<panel>` is optional); a scenario failing does not stop the others. Ends with one summary line and exits non-zero on a failure.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadEnv } from './lib/env.mjs';
import { startGateway } from './lib/gateway.mjs';
import { bootstrap, newBuyer, seedCatalogue } from './lib/bootstrap.mjs';
import { captureFailure, launch } from './lib/browser.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const filters = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const listOnly = process.argv.includes('--list');

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'lib' || entry.name === 'node_modules') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(full);
    else if (entry.name.endsWith('.scenario.mjs')) yield full;
  }
}

async function discover() {
  const found = [];

  for (const file of [...walk(root)].sort()) {
    const rel = path.relative(root, file);
    const mod = await import(pathToFileURL(file).href);
    const list = Array.isArray(mod.scenarios) ? mod.scenarios : [mod.default].filter(Boolean);

    for (const scenario of list) found.push({ ...scenario, file: rel });
  }

  const wanted = (s) =>
    !filters.length ||
    filters.some((f) => s.file.includes(f) || String(s.id).toLowerCase().includes(f.toLowerCase()));

  return found
    .filter(wanted)
    .sort((a, b) => String(a.id).localeCompare(String(b.id), 'en', { numeric: true }));
}

const scenarios = await discover();

if (listOnly) {
  for (const s of scenarios) console.log(`${s.id}\t${s.file}\t${s.title}`);
  process.exit(0);
}

if (!scenarios.length) {
  console.error(`no scenario matches ${JSON.stringify(filters)}`);
  process.exit(2);
}

const env = loadEnv();
const gateway = await startGateway(env.gatewayPort);
let browser;
let failed = 0;
let executed = 0;

try {
  const admin = await bootstrap(env, gateway);
  let catalogue;
  browser = await launch();

  const ctx = {
    env,
    admin,
    gateway,
    browser,
    catalogue: async () => (catalogue ??= await seedCatalogue(admin)),
    buyer: (label) => newBuyer(env, admin, label),
  };

  for (const scenario of scenarios) {
    const started = Date.now();
    executed++;

    try {
      await scenario.run(ctx);
      console.log(
        `PASS ${scenario.id} ${scenario.title} (${((Date.now() - started) / 1000).toFixed(1)}s)`,
      );
    } catch (error) {
      failed++;
      const shots = await captureFailure(scenario.id).catch(() => []);
      console.log(
        `FAIL ${scenario.id} ${scenario.title} (${((Date.now() - started) / 1000).toFixed(1)}s)\n  ${String(
          error?.message
            ? `${error.name}: ${error.message}\n${error.stack}`
            : error?.stack || error,
        )
          .split('\n')
          .slice(0, 12)
          .join('\n  ')}${shots.length ? `\n  screenshots: ${shots.join(', ')}` : ''}`,
      );
    }
  }
} finally {
  await browser?.close().catch(() => {});
  await gateway.close().catch(() => {});
}

console.log(`E2E-BROWSER-SUMMARY executed=${executed} failed=${failed} skipped=0`);
process.exit(failed ? 1 : 0);
