import { describe, expect, test } from 'bun:test';
import './sdkMocks.js';

const { registerTheme } = await import('../../register.js?real');

function fakePano({ withCart = true } = {}) {
  const calls = { rightComponents: [], hooks: [], pages: [] };
  const pano = {
    calls,
    ui: {
      page: { register: (p) => calls.pages.push(p) },
      nav: { site: { editNavLinks: () => {} } },
    },
  };
  if (withCart) {
    pano.ui.nav.rightComponents = { edit: (fn) => calls.rightComponents.push(fn([])) };
    pano.ui.hook = { register: (h) => calls.hooks.push(h) };
    // the profile items (14 §5 rows 9-10) are no subject here; a host that has the namespaces keeps the output quiet
    pano.ui.nav.profileDropdown = { edit: () => {} };
    pano.ui.profile = { content: { edit: () => {} }, nav: { edit: () => {} } };
  }
  return pano;
}

describe('registerTheme cart items', () => {
  test('registers the nav cart (priority 50) and the offcanvas on theme:top without loading', () => {
    const pano = fakePano();
    registerTheme(pano);
    expect(pano.calls.rightComponents[0]).toHaveLength(1);
    expect(pano.calls.rightComponents[0][0]).toMatchObject({ id: 'market-cart', priority: 50 });
    expect(pano.calls.hooks).toHaveLength(1);
    expect(pano.calls.hooks[0]).toMatchObject({ name: 'theme:top', skipLoad: true });
  });

  test('a theme without the namespaces keeps the store page and warns', () => {
    const warn = console.warn;
    const seen = [];
    console.warn = (...a) => seen.push(a);
    const pano = fakePano({ withCart: false });
    registerTheme(pano);
    console.warn = warn;
    expect(pano.calls.pages.map((p) => p.path)).toEqual([
      '/store',
      '/store/order/[id]',
      '/store/[slug]',
      '/store/checkout',
      '/profile/purchases',
      '/profile/credits',
      '/profile/subscriptions',
      '/profile/creator',
    ]);
    // nav-cart, cart-offcanvas, profile-dropdown and profile-nav are each skipped with one warning
    expect(seen).toHaveLength(4);
  });
});
