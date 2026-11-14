// Merges the locale fragments (src/locales/{core,panel,theme,webhook}/<lang>.json) into one file per language in
// the git-ignored src/main/resources/locales/, the same as the Gradle task `mergeLocales` (build.gradle.kts).
//
// In development mode Pano reads src/main/resources/locales/ of the plugin source on every request, but `bun dev`
// (rollup) only rebuilds the UI: without this merge an edited fragment never reaches the page. rollup.config.js
// runs it at the start of every (watch) build and watches the fragments, so a saved translation shows on F5.
//
// Usage: node scripts/merge-locales.mjs   (exit 1 with the reason when the fragments do not merge)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const LANGS = ['tr', 'en-US', 'ru'];

/** @param {string} root  the plugin root */
export function localeFragmentFiles(root = process.cwd()) {
  const srcDir = path.join(root, 'src', 'locales');
  return fs
    .readdirSync(srcDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort()
    .flatMap((dir) => LANGS.map((lang) => path.join(srcDir, dir, `${lang}.json`)));
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

function merge(into, from, prefix, where) {
  for (const [key, value] of Object.entries(from)) {
    const at = prefix ? `${prefix}.${key}` : key;
    if (!(key in into)) into[key] = value;
    else if (isObject(into[key]) && isObject(value)) merge(into[key], value, at, where);
    else throw new Error(`mergeLocales: duplicate leaf key '${at}' (${where})`);
  }
}

function leaves(value, prefix, out) {
  if (isObject(value))
    for (const [key, child] of Object.entries(value))
      leaves(child, prefix ? `${prefix}.${key}` : key, out);
  else out.add(prefix);
  return out;
}

/**
 * Merges the fragments and writes src/main/resources/locales/<lang>.json. Throws when a fragment is missing, two
 * fragments set the same key, or the languages do not have the same key set.
 * @param {string} root  the plugin root
 */
export function mergeLocales(root = process.cwd()) {
  const srcDir = path.join(root, 'src', 'locales');
  const outDir = path.join(root, 'src', 'main', 'resources', 'locales');
  const fragments = fs
    .readdirSync(srcDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();

  /** @type {Record<string, object>} */
  const merged = {};
  for (const lang of LANGS) {
    const out = {};
    for (const dir of fragments) {
      const file = path.join(srcDir, dir, `${lang}.json`);
      if (!fs.existsSync(file)) throw new Error(`mergeLocales: missing ${dir}/${lang}.json`);
      merge(out, JSON.parse(fs.readFileSync(file, 'utf8')), '', `${dir}/${lang}.json`);
    }
    merged[lang] = out;
  }

  const base = leaves(merged[LANGS[0]], '', new Set());
  for (const lang of LANGS.slice(1)) {
    const keys = leaves(merged[lang], '', new Set());
    const missing = [...base]
      .filter((key) => !keys.has(key))
      .sort()
      .slice(0, 20);
    const extra = [...keys]
      .filter((key) => !base.has(key))
      .sort()
      .slice(0, 20);
    if (missing.length || extra.length) {
      throw new Error(
        `mergeLocales: key set of ${lang} differs from ${LANGS[0]}: missing=[${missing}] extra=[${extra}]`,
      );
    }
  }

  fs.mkdirSync(outDir, { recursive: true });
  for (const lang of LANGS)
    fs.writeFileSync(
      path.join(outDir, `${lang}.json`),
      `${JSON.stringify(merged[lang], null, 2)}\n`,
    );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    mergeLocales();
    console.log('[pano] locales merged into src/main/resources/locales/');
  } catch (error) {
    console.error(`[pano] ${error.message}`);
    process.exit(1);
  }
}
