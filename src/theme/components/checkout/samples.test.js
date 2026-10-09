import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';

// The views of the cart, the checkout and the order (step B of doc 02 section 3) ship sample data for the view catalogue (doc 02 section 7) and
// no longer import the stateful shims (rule V1) or read the Svelte context (rule V5). A Svelte component cannot be rendered under bun here, so the
// contracts are asserted on the sources: every view has a samples file with a `filled` state, every state hands over the props the view requires,
// and the standard states are either present or named in `notApplicable`.
const FOLDERS = ['cart', 'checkout', 'order'];
const STANDARD = ['empty', 'filled', 'error', 'loading'];
const here = path.dirname(new URL(import.meta.url).pathname);
const root = path.join(here, '..');

const views = FOLDERS.flatMap((folder) =>
  fs
    .readdirSync(path.join(root, folder))
    .filter((file) => file.endsWith('.svelte'))
    .sort()
    .map((file) => ({
      folder,
      name: file.replace(/\.svelte$/, ''),
      file: path.join(root, folder, file),
    })),
);

/** The props a view cannot do without: names destructured from `$props()` with no default. */
function requiredProps(source) {
  const match = /let \{([\s\S]*?)\} = \$props\(\)/.exec(source);
  if (!match) return [];

  const entries = [];
  let depth = 0;
  let current = '';

  for (const char of match[1]) {
    if ('([{'.includes(char)) depth++;
    if (')]}'.includes(char)) depth--;
    if (char === ',' && depth === 0) {
      entries.push(current);
      current = '';
    } else current += char;
  }
  entries.push(current);

  return entries
    .map((entry) => entry.replace(/\/\/.*$/gm, '').trim())
    .filter((entry) => entry && !entry.startsWith('...') && !entry.includes('='))
    .map((entry) => entry.split(':')[0].trim());
}

describe('the views of the cart, checkout and order', () => {
  test('there are views to check', () => {
    expect(views.length).toBeGreaterThanOrEqual(26);
  });

  for (const view of views) {
    const source = fs.readFileSync(view.file, 'utf8');

    describe(view.name, () => {
      test('imports no shim and reads no context (V1, V5)', () => {
        expect(source).not.toMatch(/i18n\.js/);
        expect(source).not.toMatch(/\b(get|set|has)Context\b/);
        expect(source).not.toMatch(
          /from '(\.\.\/)+stores\/(session|cart|clock|currency|checkoutDraft|storeSettings|profileNav)\.js'/,
        );
        expect(source).not.toMatch(/from '(\.\.\/)+utils\/(api|format|host)\.js'/);
        expect(source).not.toMatch(/bindSession|hostSession/);
      });

      test('is wired to the market controllers by name', () => {
        const names = [...source.matchAll(/\.require\('([a-zA-Z]+)'/g)].map((m) => m[1]);
        const known = fs
          .readdirSync(path.join(root, '..', 'controllers'))
          .filter((file) => file.endsWith('.js') && !file.startsWith('_'))
          .map((file) => file.replace(/\.js$/, ''));

        for (const name of names) expect(known).toContain(name);
      });

      test('has a samples file with every state it can show', async () => {
        const file = path.join(root, view.folder, `${view.name}.samples.js`);

        expect(fs.existsSync(file)).toBe(true);

        const mod = await import(file);
        const notApplicable = mod.notApplicable ?? [];
        const states = mod.default;

        expect(typeof states).toBe('object');
        expect(states.filled).toBeDefined();

        for (const standard of STANDARD) {
          expect(notApplicable).toBeInstanceOf(Array);
          if (!notApplicable.includes(standard)) expect(states[standard]).toBeDefined();
          else expect(states[standard]).toBeUndefined();
        }

        const required = requiredProps(source);

        for (const [key, entry] of Object.entries(states)) {
          const state = typeof entry === 'function' ? entry() : entry;

          expect(typeof state, `${view.name}.${key}`).toBe('object');
          for (const prop of required) {
            expect(state.props ?? {}, `${view.name}.${key} lacks ${prop}`).toHaveProperty(prop);
          }
          if (state.session !== undefined) expect(['guest', 'user']).toContain(state.session);
        }
      });
    });
  }

  test('the controller names samples patch exist', async () => {
    const known = new Set(
      fs
        .readdirSync(path.join(root, '..', 'controllers'))
        .filter((file) => file.endsWith('.js') && !file.startsWith('_'))
        .map((file) => `market/${file.replace(/\.js$/, '')}`),
    );

    for (const view of views) {
      const mod = await import(path.join(root, view.folder, `${view.name}.samples.js`));

      for (const entry of Object.values(mod.default)) {
        const state = typeof entry === 'function' ? entry() : entry;

        for (const name of Object.keys(state.controllers ?? {})) expect(known.has(name)).toBe(true);
      }
    }
  });
});
