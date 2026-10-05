import svelte from 'rollup-plugin-svelte';
import resolve from '@rollup/plugin-node-resolve';
import del from 'rollup-plugin-delete';
import terser from '@rollup/plugin-terser';
import fs from 'node:fs';
import path from 'node:path';

const dev = process.env.DEV === 'true';
const production = !dev;

const bundleSdk = process.env.BUNDLE_SDK === 'true';

// --- Lane-scoped check builds (D-WB2) ----------------------------------------------
// MARKET_UI_SIDE=panel|theme compiles one side only into build/ui-check/<side>/ (the other
// side's register.js is replaced by an empty stub, so none of its files are read) and never
// touches src/main/resources/plugin-ui. Unset = the real full build.
const side = process.env.MARKET_UI_SIDE || '';
if (side && side !== 'panel' && side !== 'theme') {
  console.error(`[pano] ERROR: MARKET_UI_SIDE must be 'panel' or 'theme', got '${side}'.`);
  process.exit(1);
}
const outRoot = side ? `build/ui-check/${side}` : 'src/main/resources/plugin-ui';
const otherSideRegister = side
  ? path.resolve(`src/${side === 'panel' ? 'theme' : 'panel'}/register.js`)
  : null;

// --- Svelte version guard ---------------------------------------------------
// The svelte COMPILER this plugin builds with must match the runtime the Pano
// host (theme/panel) serves in the browser — compiled output and runtime are only
// guaranteed compatible at the exact same version (svelte's internal API may
// change even in patch releases). @panomc/sdk pins the correct version as a
// regular dependency, so the plugin must NOT declare svelte itself: an override
// can drift from the host runtime and break the plugin at hydration.
function checkSvelteVersion() {
  const read = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

  let sdkPin = null;
  try {
    sdkPin =
      read(path.resolve('node_modules/@panomc/sdk/package.json')).dependencies?.svelte ?? null;
  } catch {
    // sdk not installed — rollup will fail on its own with a clearer error.
  }

  let installed = null;
  try {
    installed = read(path.resolve('node_modules/svelte/package.json')).version;
  } catch {
    // svelte missing entirely — rollup-plugin-svelte will fail on its own.
  }

  let ownDecl = null;
  try {
    const own = read(path.resolve('package.json'));
    ownDecl =
      own.dependencies?.svelte ??
      own.devDependencies?.svelte ??
      own.peerDependencies?.svelte ??
      null;
  } catch {
    // no readable package.json — nothing to validate.
  }

  if (ownDecl) {
    console.warn(
      `[pano] WARNING: package.json declares svelte ${ownDecl}, but the svelte version ` +
        `comes from @panomc/sdk. A local override can drift from the Pano host runtime ` +
        `and break the plugin at hydration — remove the svelte entry and re-install.`,
    );
  }

  // The sdk pins an exact version; only enforce when it is one (not a range).
  if (sdkPin && /^\d/.test(sdkPin) && installed && installed !== sdkPin) {
    console.error(
      `[pano] ERROR: installed svelte is ${installed} but @panomc/sdk requires exactly ` +
        `${sdkPin}. Compiled plugin output is only compatible with the Pano host runtime ` +
        `at the same version. Remove any svelte override from package.json and re-install.`,
    );
    process.exit(1);
  }

  if (!sdkPin && installed) {
    console.warn(
      `[pano] WARNING: the installed @panomc/sdk does not pin a svelte version; building ` +
        `with svelte ${installed}. Make sure it matches the Pano host runtime version.`,
    );
  }
}
checkSvelteVersion();

function manifestPlugin() {
  return {
    name: 'manifest',
    writeBundle(options, bundle) {
      const dir = options.dir;
      const manifestPath = path.join(dir, 'manifest.json');
      const files = Object.keys(bundle);
      fs.writeFileSync(manifestPath, JSON.stringify(files, null, 2));
    },
  };
}

// --- Entry facade -------------------------------------------------------------
// The host imports the entry with a cache-busting query (client.mjs?v=<uiHash>)
// while lazy chunks import it query-less ('./client.mjs'). The browser keys its
// module map by FULL URL, so any module state living in the entry file would be
// evaluated twice and lazy chunks would read unassigned exports (e.g. `pano`
// undefined → "can't access property ui"). To prevent that, the build goes
// through a virtual facade entry and src/main.js is forced into a shared chunk:
// the emitted client.mjs/server.mjs is a pure re-export facade with no state of
// its own, and all module state lives at a single query-less chunk URL.
const realEntry = path.resolve('src/main.js');
const virtualEntryId = '\0pano-entry-facade';

function entryFacadePlugin() {
  return {
    name: 'pano-entry-facade',
    resolveId(id) {
      if (id === 'pano:entry') return virtualEntryId;
    },
    load(id) {
      if (id === virtualEntryId) {
        return (
          `export * from ${JSON.stringify(realEntry)};\n` +
          `export { default } from ${JSON.stringify(realEntry)};\n`
        );
      }
    },
  };
}

function sideStubPlugin() {
  const stubId = '\0pano-other-side-stub';
  return {
    name: 'pano-other-side-stub',
    async resolveId(source, importer) {
      if (!otherSideRegister || !importer) return null;
      const resolved = await this.resolve(source, importer, { skipSelf: true });
      if (resolved && path.resolve(resolved.id) === otherSideRegister) return stubId;
      return null;
    },
    load(id) {
      if (id !== stubId) return null;
      const name = side === 'panel' ? 'registerTheme' : 'registerPanel';
      return `export function ${name}() {}\n`;
    },
  };
}

// Lane-scoped check builds only: the panel pages of 13 §2.3 are written by several slices, so
// register.js may import a page that does not exist yet. In a check build such a page is replaced by
// an empty stub (listed on stderr). MARKET_STRICT=1 and the real full build never stub: a missing page
// fails the build there (final gate).
const panelRegister = path.resolve('src/panel/register.js');
const missingStubIds = new Set();
function missingPageStubPlugin() {
  const prefix = '\0pano-missing-page:';
  return {
    name: 'pano-missing-page-stub',
    resolveId(source, importer) {
      if (!side || process.env.MARKET_STRICT === '1' || !importer) return null;
      if (path.resolve(importer) !== panelRegister || !source.startsWith('./')) return null;
      const file = path.resolve(path.dirname(importer), source);
      if (fs.existsSync(file)) return null;
      missingStubIds.add(path.relative(process.cwd(), file));
      return prefix + source;
    },
    load(id) {
      if (!id.startsWith(prefix)) return null;
      return 'export default function MissingPage() {}\n';
    },
    buildEnd() {
      if (missingStubIds.size)
        console.warn(
          `[pano] check build: ${missingStubIds.size} page file(s) not written yet, stubbed: ` +
            [...missingStubIds].sort().join(', '),
        );
    },
  };
}

const baseConfig = {
  input: 'pano:entry',
  output: {
    format: 'es',
    chunkFileNames: '[name]-[hash].js', // Chunk file naming
    manualChunks(id) {
      if (id === realEntry) return 'main';
    },
  },
  plugins: [
    entryFacadePlugin(),
    sideStubPlugin(),
    missingPageStubPlugin(),
    del({
      targets: [`${outRoot}/*`], // Always clean the output folder of this build
      runOnce: true, // Run only once
    }),
    production && terser(),
    manifestPlugin(),
  ],
  preserveEntrySignatures: 'strict',
};

export default [
  // Server configuration
  {
    ...baseConfig,
    output: {
      ...baseConfig.output,
      dir: `${outRoot}/server`, // Server directory
      entryFileNames: 'server.mjs', // Server entry file
    },
    plugins: [
      ...baseConfig.plugins,
      resolve({
        dedupe: ['svelte'],
      }),
      svelte({
        compilerOptions: {
          generate: 'server',
          css: 'external',
        },
        emitCss: false,
      }),
    ],
  },
  // Client configuration
  {
    ...baseConfig,
    output: {
      ...baseConfig.output,
      dir: `${outRoot}/client`, // Client directory
      entryFileNames: 'client.mjs', // Client entry file
    },
    // Bare 'svelte'/'svelte/*', 'svelte-i18n' and '@panomc/sdk*' specifiers stay
    // EXTERNAL in both dev and production: the host (theme/panel) injects an import
    // map that resolves them to stable /runtime shim modules, and each shim re-exports
    // the HOST bundle's own live module instance. Host pages and plugins therefore
    // share a single Svelte runtime and a single SDK instance (same effect scheduler,
    // same stores/contexts). Bundling a private SDK copy into the plugin would split
    // store/context state from the host's instance, so the SDK must never be bundled
    // in normal builds. BUNDLE_SDK=true is an escape hatch that bundles everything
    // (self-contained build, no host import map required).
    // NOTE: the match is exact/subpath, NOT a prefix — the host import map only
    // provides these specifiers. A prefix match would leave third-party packages like
    // 'svelte-select' as unresolvable bare imports in the browser; such dependencies
    // must be bundled into the plugin.
    external: (id) => {
      if (bundleSdk) return false;
      return (
        id === 'svelte' ||
        id.startsWith('svelte/') ||
        id === 'svelte-i18n' ||
        id === '@panomc/sdk' ||
        id.startsWith('@panomc/sdk/')
      );
    },
    plugins: [
      ...baseConfig.plugins,
      resolve({
        browser: true,
        dedupe: ['svelte', '@panomc/sdk'],
      }),
      svelte({
        compilerOptions: {
          generate: 'client',
          css: 'external',
          dev,
        },
        emitCss: false,
      }),
    ],
  },
];
