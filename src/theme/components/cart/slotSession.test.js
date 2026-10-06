import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';

// The browser scenario TH-15 (E2E-16) found that a signed-in visitor on a non-store page (the home page) saw no cart badge at all and, when
// the offcanvas was opened there, a browser cart instead of the account cart: the two cart slot components never bound the host session
// (14 section 4.3: "called at the top of every market page and slot component"), so on those pages `user` stayed null. A Svelte component
// cannot be rendered under bun here, so the contract is asserted on the sources, the behaviour by the browser scenario.
const SLOT_COMPONENTS = ['NavCart.svelte', 'CartOffcanvas.svelte'];

describe('cart slot components bind the host session', () => {
  for (const file of SLOT_COMPONENTS) {
    const source = fs.readFileSync(new URL(`./${file}`, import.meta.url), 'utf8');
    const script = source.slice(source.indexOf('<script>'));

    test(`${file} imports getContext, bindSession and hostSession`, () => {
      expect(script).toMatch(/import \{[^}]*\bgetContext\b[^}]*\} from 'svelte';/);
      expect(script).toMatch(
        /import \{[^}]*\bbindSession\b[^}]*\bhostSession\b[^}]*\} from '\.\.\/\.\.\/stores\/session\.js';/,
      );
    });

    test(`${file} calls bindSession(hostSession(getContext)) at the top level of its script`, () => {
      const call = script.indexOf('bindSession(hostSession(getContext));');

      expect(call).toBeGreaterThan(-1);
      // before any hook or effect of the component (a top-level call, the same place the pages make it)
      for (const later of ['onMount(', '$effect(']) {
        const at = script.indexOf(later);
        if (at > -1) expect(call).toBeLessThan(at);
      }
    });
  }

  test('both components are the ones register.js puts into the navbar and the theme:top hook', () => {
    const register = fs.readFileSync(new URL('../../register.js', import.meta.url), 'utf8');

    for (const file of SLOT_COMPONENTS) expect(register).toContain(`components/cart/${file}`);
  });
});
