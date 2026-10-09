import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import * as cartView from './cartView.js';

// The browser smoke (E2E-13) found `navVisible is not defined` on every storefront page: the template used a helper of
// cartView.js that the script block never imported. Svelte compiles that without an error, so the import list is asserted here.
describe('NavCart imports', () => {
  const source = fs.readFileSync(new URL('./NavCart.svelte', import.meta.url), 'utf8');
  const imported = new Set(
    (source.match(/import \{([^}]*)\} from '\.\/cartView\.js';/)?.[1] ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
  );

  test('every cartView helper the component calls is imported', () => {
    const template = source.slice(0, source.indexOf('<script>'));
    const script = source.slice(source.indexOf('<script>'));
    const used = Object.keys(cartView).filter((name) =>
      new RegExp(`\\b${name}\\(`).test(template + script),
    );

    expect(used.length).toBeGreaterThan(0);

    for (const name of used) expect(imported.has(name)).toBe(true);
  });

  test('the click leaves the offcanvas alone and leads to the checkout only without a host', () => {
    expect(source).toContain('onclick={openCheckout}');
    expect(source).toContain('if (hasOffcanvasHost(document)) return;');
    expect(source).toContain('goto(checkoutHref(getPanoContext().context));');
    // the theme markup is unchanged: Bootstrap's data attributes still open the offcanvas
    expect(source).toContain('data-bs-target="#marketCartOffcanvas"');
  });
});
