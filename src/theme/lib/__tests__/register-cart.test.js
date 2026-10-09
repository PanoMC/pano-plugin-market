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

describe('registerTheme conditional items', () => {
  test('the navbar cart and the offcanvas are view metadata: register.js adds neither', () => {
    const pano = fakePano();
    registerTheme(pano);
    expect(pano.calls.rightComponents).toEqual([]);
    expect(pano.calls.hooks).toEqual([]);
    expect(pano.calls.pages).toEqual([]);
  });

  test('without profile-nav the profile block is injected by view id, not by component', () => {
    const items = [];
    const pano = fakePano();
    pano.ui.profile.content.edit = (fn) => items.push(...fn([]));
    registerTheme(pano);
    expect(items).toEqual([{ id: 'market', priority: 50, view: 'market:MarketProfileBlock' }]);
  });

  test('a theme without the namespaces keeps the nav link and warns', () => {
    const warn = console.warn;
    const seen = [];
    console.warn = (...a) => seen.push(a);
    const pano = fakePano({ withCart: false });
    registerTheme(pano);
    console.warn = warn;
    expect(pano.calls.pages).toEqual([]);
    // profile-dropdown and profile-nav are each skipped with one warning
    expect(seen).toHaveLength(2);
  });
});
