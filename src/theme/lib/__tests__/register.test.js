import { describe, expect, test } from 'bun:test';
import './sdkMocks.js';

// a query string keeps this import apart from the mocked './theme/register.js' of main.test.js
const { registerTheme, registerOptional } = await import('../../register.js?real');
const host = await import('../../utils/host.js');

function fakePano({ failNav = false } = {}) {
  const calls = { pages: [], nav: [] };
  return {
    calls,
    ui: {
      page: { register: (p) => calls.pages.push(p) },
      nav: {
        site: {
          editNavLinks: (fn) => {
            if (failNav) throw new Error('no nav');
            calls.nav.push(fn([]));
          },
        },
      },
    },
  };
}

describe('registerTheme', () => {
  test('binds the host and registers the store page and nav link', () => {
    const pano = fakePano();
    registerTheme(pano);
    expect(host.getPano()).toBe(pano);
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
    expect(pano.calls.nav[0][0].href).toBe('/store');
    expect(pano.calls.nav[0][0].text).toBe('plugins.pano-plugin-market.nav-store');
  });

  test('registerOptional swallows a failure with a warning', () => {
    const warn = console.warn;
    const seen = [];
    console.warn = (...a) => seen.push(a);
    let after = false;
    registerOptional('cart', () => {
      throw new Error('no namespace');
    });
    registerOptional('next', () => (after = true));
    console.warn = warn;
    expect(seen).toHaveLength(1);
    expect(after).toBe(true);
  });
});
