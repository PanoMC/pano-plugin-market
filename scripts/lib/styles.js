// The style lint of the plugin kit (doc 03 section 3) at `badge` level, for the check scripts: `check:static` reports the
// class rules, `check:theme` the `style=` and `<style>` rules, so no finding is printed twice. The lint itself is
// `checkStyles` of @panomc/plugin-kit (the same one `pano-plugin check --styles badge` runs); the kit has no subpath export
// for it yet, so it is loaded by file from the installed package. Options come from pano.plugin.js.
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import config from '../../pano.plugin.js';
import { root as pluginRoot, rel, walk } from './common.js';

export const NAMESPACE = 'market';
/** The views that may set more than custom properties in `style=` (pano.plugin.js `styles.styleAttrAllow`). */
export const STYLE_ATTR_FILES = config.styles?.styleAttrAllow ?? [];
/** Findings of these rules are the `<style>` / `style=` ones; every other rule belongs to the class lint. */
export const STYLE_RULES = ['style-block-scope', 'style-attr'];

const LINT_FILE = path.join(
  pluginRoot,
  'node_modules/@panomc/plugin-kit/src/styles/check-styles.js',
);

/**
 * @param {string} root plugin root (or a throw-away copy of its `src/theme`)
 * @returns {Promise<{ rule: string, file: string, line: number, message: string, level: 'error' | 'warn' }[]>}
 */
export async function lintStyles(root = pluginRoot) {
  const { checkStyles } = await import(pathToFileURL(LINT_FILE).href);
  const views = (config.viewDirs ?? ['src/theme/views'])
    .flatMap((dir) => walk(path.join(root, dir), ['.svelte']))
    .map((file) => ({ name: path.basename(file, '.svelte'), file: rel(file, root) }));

  return checkStyles({
    root,
    ns: config.namespace ?? NAMESPACE,
    views,
    styleAttrAllow: STYLE_ATTR_FILES,
    safelist: config.styles?.safelist ?? [],
    level: 'badge',
  });
}
