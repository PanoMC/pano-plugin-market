import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';

// The pages and the store and product views (step B of doc 02 section 3) ship sample data for the view catalogue (doc 02 section 7), no longer
// import the stateful shims (rule V1) and no longer read the Svelte context (rule V5). A Svelte component cannot be rendered under bun here, so the
// contracts are asserted on the sources: every view has a samples file with a `filled` state, every state hands over the props the view requires,
// the standard states are either present or named in `notApplicable`, and the four page loads sit in their controllers (`view.controller`).
const FOLDERS = ['pages', 'pages/profile', 'components/store', 'components/product'];
const STANDARD = ['empty', 'filled', 'error', 'loading'];
const CONTEXT_CALLS = new RegExp(
  ['get', 'set', 'has'].map((verb) => `\\b${verb}Context\\b`).join('|'),
);
const theme = path.join(path.dirname(new URL(import.meta.url).pathname), '..');
const controllers = fs
  .readdirSync(path.join(theme, 'controllers'))
  .filter((file) => file.endsWith('.js') && !file.startsWith('_'))
  .map((file) => file.replace(/\.js$/, ''));

const views = FOLDERS.flatMap((folder) =>
  fs
    .readdirSync(path.join(theme, folder))
    .filter((file) => file.endsWith('.svelte'))
    .sort()
    .map((file) => ({
      folder,
      name: file.replace(/\.svelte$/, ''),
      file: path.join(theme, folder, file),
      samples: path.join(theme, folder, file.replace(/\.svelte$/, '.samples.js')),
    })),
);

/** The props a view cannot do without: names destructured from `$props()` with no default (callbacks, `on*`, are left out: a sample is data). */
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
    .map((entry) => entry.split(':')[0].trim())
    .filter((name) => !/^on[a-z]+$/.test(name));
}

describe('the pages and the store and product views', () => {
  test('there are views to check', () => {
    expect(views.length).toBeGreaterThanOrEqual(28);
  });

  for (const view of views) {
    const source = fs.readFileSync(view.file, 'utf8');

    describe(view.name, () => {
      test('imports no shim and reads no context (V1, V5)', () => {
        expect(source).not.toMatch(/i18n\.js/);
        expect(source).not.toMatch(CONTEXT_CALLS);
        expect(source).not.toMatch(/from '(\.\.\/)+utils\/(api|format|host)\.js'/);
        expect(source).not.toMatch(
          /from '(\.\.\/)+stores\/(session|cart|clock|currency|checkoutDraft|storeSettings|profileNav)\.js'/,
        );
        expect(source).not.toMatch(/bindSession|hostSession/);
      });

      test('is wired to the market controllers by name', () => {
        const names = [...source.matchAll(/\.(?:require|use|load)\('([a-zA-Z]+)'/g)].map(
          (m) => m[1],
        );

        for (const name of names) expect(controllers).toContain(name);
      });

      test('has a samples file with every state it can show', async () => {
        expect(fs.existsSync(view.samples)).toBe(true);

        const mod = await import(view.samples);
        const notApplicable = mod.notApplicable ?? [];
        const states = mod.default;

        expect(typeof states).toBe('object');
        expect(states.filled).toBeDefined();

        for (const standard of STANDARD) {
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
          for (const name of Object.keys(state.controllers ?? {})) {
            expect(controllers).toContain(name.replace(/^market\//, ''));
          }
        }
      });
    });
  }

  describe('the page loads', () => {
    const pages = {
      StorePage: ['store', "path: '/store'"],
      ProductPage: ['product', "path: '/store/[slug]'"],
      OrderPage: ['order', "path: '/store/order/[id]'"],
      CheckoutPage: ['checkout', "path: '/store/checkout'"],
    };

    for (const [page, [controller, path_]] of Object.entries(pages)) {
      test(`${page} loads through market/${controller}`, () => {
        const source = fs.readFileSync(path.join(theme, 'pages', `${page}.svelte`), 'utf8');

        expect(source).toContain(path_);
        expect(source).toContain(`controller: '${controller}'`);
        expect(source).toContain(`.load('${controller}'`);
        // the request logic is the controller's: the view keeps no request of its own in its module script
        const moduleScript = /<script module>([\s\S]*?)<\/script>/.exec(source)[1];
        expect(moduleScript).not.toMatch(/\bcall\(/);
      });
    }

    test('the controllers are server-safe loaders', async () => {
      for (const name of ['store', 'product', 'order', 'checkout']) {
        const mod = await import(path.join(theme, 'controllers', `${name}.js`));

        expect(mod.default.scope).toBe('instance');
        expect(typeof mod.default.load).toBe('function');
      }
    });
  });
});
