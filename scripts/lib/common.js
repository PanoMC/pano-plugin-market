// Shared helpers of the UI check scripts (17 §10, 13 §25, 14 §20.2).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const LANGS = ['tr', 'en-US', 'ru'];
export const PLUGIN_ID = 'pano-plugin-market';

export function parseArgs(argv = process.argv.slice(2)) {
  const out = { side: '', strict: process.env.MARKET_STRICT === '1', rest: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--side') out.side = argv[++i] ?? '';
    else if (a.startsWith('--side=')) out.side = a.slice(7);
    else if (a === '--strict') out.strict = true;
    else if (a === '--root') out.root = argv[++i];
    else out.rest.push(a);
  }
  if (out.side && out.side !== 'panel' && out.side !== 'theme') {
    console.error(`unknown --side '${out.side}' (expected panel or theme)`);
    process.exit(2);
  }
  return out;
}

export function walk(dir, exts) {
  const result = [];
  if (!fs.existsSync(dir)) return result;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') continue;
      result.push(...walk(full, exts));
    } else if (!exts || exts.some((e) => entry.name.endsWith(e))) {
      result.push(full);
    }
  }
  return result.sort();
}

export const rel = (file, base = root) => path.relative(base, file).split(path.sep).join('/');
export const read = (file) => fs.readFileSync(file, 'utf8');

export function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) if (text.charCodeAt(i) === 10) line++;
  return line;
}

export function flatten(obj, prefix = '', out = new Map()) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out);
    else out.set(key, v);
  }
  return out;
}

// Locale fragments live in src/locales/<fragment>/<lang>.json (merged by Gradle mergeLocales).
export function loadFragment(rootDir, fragment, lang) {
  const file = path.join(rootDir, 'src', 'locales', fragment, `${lang}.json`);
  if (!fs.existsSync(file)) return null;
  return flatten(JSON.parse(read(file)));
}

export function loadMerged(rootDir, fragments, lang) {
  const merged = new Map();
  for (const fragment of fragments) {
    const flat = loadFragment(rootDir, fragment, lang);
    if (flat) for (const [k, v] of flat) merged.set(k, v);
  }
  return merged;
}

export function fragmentsFor(side) {
  if (side === 'panel') return ['core', 'panel'];
  if (side === 'theme') return ['core', 'theme'];
  return ['core', 'panel', 'theme'];
}

// A rule group reports violations; "pending" rules only warn until --strict / MARKET_STRICT=1.
export class Report {
  constructor(name, { strict = false } = {}) {
    this.name = name;
    this.strict = strict;
    this.errors = [];
    this.warnings = [];
    this.notes = [];
  }
  add(rule, file, line, message, { pending = false } = {}) {
    const text = `${rule} ${file}${line ? `:${line}` : ''} ${message}`;
    if (pending && !this.strict) this.warnings.push(text);
    else this.errors.push(text);
  }
  note(text) {
    this.notes.push(text);
  }
  finish() {
    for (const n of this.notes) console.log(`[${this.name}] note: ${n}`);
    for (const w of this.warnings.slice(0, 5)) console.log(`[${this.name}] pending: ${w}`);
    if (this.warnings.length > 5)
      console.log(
        `[${this.name}] pending: ... ${this.warnings.length - 5} more (use --strict to fail on them)`,
      );
    if (this.errors.length) {
      for (const e of this.errors) console.error(`[${this.name}] FAIL ${e}`);
      console.error(`[${this.name}] ${this.errors.length} violation(s)`);
      return 1;
    }
    console.log(`[${this.name}] ok (${this.warnings.length} pending warning(s))`);
    return 0;
  }
}
