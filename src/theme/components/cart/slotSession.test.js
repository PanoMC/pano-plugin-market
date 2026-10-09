import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';

// The browser scenario TH-15 (E2E-16) found that a signed-in visitor on a non-store page (the home page) saw no cart badge at all and, when
// the offcanvas was opened there, a browser cart instead of the account cart: the two cart slot components never bound the host session, so
// on those pages the user stayed null. Since the controllers step nobody binds anything: the eager `market/session` controller follows the
// host session (`host.onSession`) and the eager `market/cart` controller initialises itself with it, so the slot components only have to read
// them (rule V5: no Svelte context between views). A Svelte component cannot be rendered under bun here, so the contract is asserted on the
// sources, the behaviour by the browser scenario.
const SLOT_COMPONENTS = ['NavCart.svelte', 'CartOffcanvas.svelte'];

describe('cart slot components read the session from the controllers', () => {
  for (const file of SLOT_COMPONENTS) {
    const source = fs.readFileSync(new URL(`./${file}`, import.meta.url), 'utf8');
    const script = source.slice(source.indexOf('<script>'));

    test(`${file} binds nothing and uses no context`, () => {
      expect(script).not.toContain('get' + 'Context');
      expect(script).not.toContain('bindSession');
      expect(script).not.toContain('hostSession');
      expect(script).not.toMatch(/stores\/(session|cart|storeSettings)\.js/);
    });

    test(`${file} takes the cart from the market/cart controller`, () => {
      expect(script).toContain("import { plugin } from '@panomc/sdk/controllers';");
      expect(script).toContain("market.require('cart')");
    });
  }

  test('NavCart reads the user from the market/session controller', () => {
    const source = fs.readFileSync(new URL('./NavCart.svelte', import.meta.url), 'utf8');

    expect(source).toContain("market.require('session')");
    expect(source).toContain('session.state.user');
  });

  test('both components inject themselves by view metadata (register.js has no entry for them)', () => {
    const register = fs.readFileSync(new URL('../../register.js', import.meta.url), 'utf8');
    const sources = SLOT_COMPONENTS.map((file) =>
      fs.readFileSync(new URL(`./${file}`, import.meta.url), 'utf8'),
    );

    for (const file of SLOT_COMPONENTS) expect(register).not.toContain(`components/cart/${file}`);
    // a widget too (doc 06 section 3.5): `<pano-market-nav-cart>`
    expect(sources[0].replace(/\s+/g, ' ')).toContain(
      "export const view = { slot: 'navbar-right', id: 'market-cart', priority: 50, block: true, widget: true, };",
    );
    expect(sources[1]).toContain("export const view = { hook: 'theme:top', skipLoad: true };");
  });
});
