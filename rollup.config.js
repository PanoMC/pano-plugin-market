import fs from 'node:fs';
import path from 'node:path';
import { panoPlugin } from '@panomc/plugin-kit/rollup';

// The build is the kit preset (@panomc/plugin-kit): server and client bundles, the entry facade, the svelte
// version guard against the SDK pin, PANO_SDK_DIR, DEV and BUNDLE_SDK all live there. This file only keeps what is
// specific to Market: the lane-scoped check builds, the missing-page stub and the development preview seam. The views obey the
// import rules V1 and V5 of the kit (no PANO_VIEW_IMPORTS migration mode): a violation fails the build.

// --- SDK source -----------------------------------------------------------------------
// The published @panomc/sdk pinned in package.json lags behind the views the theme UI imports (@panomc/sdk/views,
// NoContent, ...). Inside the umbrella workspace the build therefore takes the sibling theme-core copy when
// PANO_SDK_DIR is not set; outside it, the build stops with one clear line instead of a rollup export error.
if (!process.env.PANO_SDK_DIR) {
  const workspaceSdk = path.resolve('../../../theme-core/packages/sdk');
  if (fs.existsSync(path.join(workspaceSdk, 'package.json'))) {
    process.env.PANO_SDK_DIR = workspaceSdk;
  } else {
    const installed = path.resolve('node_modules/@panomc/sdk/package.json');
    const exportsMap = fs.existsSync(installed)
      ? JSON.parse(fs.readFileSync(installed, 'utf8')).exports || {}
      : {};
    if (!exportsMap['./views']) {
      console.error(
        '[pano] ERROR: the installed @panomc/sdk has no "./views" export; set PANO_SDK_DIR to a theme-core packages/sdk checkout.'
      );
      process.exit(1);
    }
  }
}

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

// --- Development preview seam ---------------------------------------------------------
// Every import of the host ApiUtil from this plugin's own files is redirected to src/mock/seam.js, the
// one place where the development-only preview mode (fake data, see src/mock/) can answer a request.
// The seam itself imports the real module (importer === seamFile is left alone). In the client build the
// host ApiUtil is a host specifier (external); `bundle` takes the seam import out of that rule.
const seamFile = path.resolve('src/mock/seam.js');
const API_ID = '@panomc/sdk/utils/api';
// `@panomc/sdk/plugin-api` (the plugin-scoped client of the panel) is a virtual module of the kit that imports
// `createPluginApi` from the host ApiUtil; the seam exports `createPluginApi` too (prefixing the paths and calling the seam),
// so that import is redirected like every other one and preview mode answers the plugin-scoped client as well.
const isSeamImport = (id, importer) =>
  id === API_ID && importer && path.resolve(importer) !== seamFile;
function apiSeamPlugin() {
  return {
    name: 'pano-market-api-seam',
    resolveId(source, importer) {
      if (isSeamImport(source, importer)) return seamFile;
      return null;
    },
  };
}

const builds = await panoPlugin({
  outDir: outRoot,
  plugins: [apiSeamPlugin(), sideStubPlugin(), missingPageStubPlugin()],
  bundle: isSeamImport,
});

export default builds;
