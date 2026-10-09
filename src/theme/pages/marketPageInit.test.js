import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';

// The browser scenario TH-13 (E2E-16): a guest with a cart signs in on another page and then opens the store through the navbar. The
// session hook that initialises the cart only runs at the first bind and on a login, and outside the market pages it stays lazy
// (14 section 7.3), so the store opened later never loaded the account cart: the badge showed the empty server cart while the guest
// lines still sat unmerged in the browser. The market pages that carry the cart badge therefore start the cart themselves on mount.
describe('market pages start the cart on mount', () => {
  for (const page of ['StorePage.svelte', 'ProductPage.svelte']) {
    test(`${page} calls cartActions().autoInit() inside onMount`, () => {
      const source = fs.readFileSync(new URL(`./${page}`, import.meta.url), 'utf8');
      const mount = source.indexOf('onMount(() => {');
      const call = source.indexOf('cartActions().autoInit();', mount);

      expect(mount).toBeGreaterThan(-1);
      expect(call).toBeGreaterThan(mount);
      // inside the same callback as the SSR-safe browser work (before its cleanup return)
      expect(call).toBeLessThan(source.indexOf('return () =>', mount));
    });
  }
});
