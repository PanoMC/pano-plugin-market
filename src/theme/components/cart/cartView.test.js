import { describe, expect, test } from 'bun:test';
import {
  DEFAULT_CHECKOUT_URL,
  OFFCANVAS_ID,
  checkoutHref,
  countCacheValue,
  errorKey,
  fieldPairs,
  hasOffcanvasHost,
  isDiscounted,
  metaRows,
  navVisible,
  quoteRows,
  resolveNavCount,
  showCredit,
  subtotalOf,
  viewState,
} from './cartView.js';
import { lineKey } from '../../lib/lineKey.js';

const base = { mode: 'GUEST', status: 'IDLE', lines: [], count: 0, quote: null, quoteStale: false };
const line = { productId: 1, variantId: 0, quantity: 2, fieldValues: {}, targetServerId: null };

describe('viewState', () => {
  test('follows the table of 14 7.1', () => {
    expect(viewState({ ...base, mode: 'NONE' })).toBe('LOADING');
    expect(viewState({ ...base, mode: 'SERVER', status: 'LOADING' })).toBe('LOADING');
    expect(viewState(base)).toBe('EMPTY');
    const full = { ...base, lines: [line], count: 2 };
    expect(viewState(full)).toBe('META_ROWS');
    expect(viewState({ ...full, quote: { lines: [] }, quoteStale: true })).toBe('META_ROWS');
    expect(viewState({ ...full, quote: { lines: [] } })).toBe('QUOTE_ROWS');
    expect(viewState({ ...full, status: 'ERROR' })).toBe('ERROR_ROWS');
    expect(viewState({ ...base, status: 'ERROR' })).toBe('ERROR');
  });
});

describe('rows', () => {
  test('metaRows price x quantity from meta', () => {
    const rows = metaRows([{ ...line, meta: { name: 'Rank', slug: 'rank', price: 1.5 } }]);
    expect(rows[0].lineTotal).toBe(3);
    expect(rows[0].key).toBe(lineKey(line));
  });

  test('quoteRows hides bundle children and keeps errors and credit price', () => {
    const rows = quoteRows({
      lines: [
        {
          productId: 1,
          quantity: 1,
          kind: 'BUNDLE',
          unitPrice: 5,
          listUnitPrice: 8,
          creditUnitPrice: 50,
          maxQuantity: 1,
          errors: ['OUT_OF_STOCK'],
          lineTotal: 5,
        },
        { productId: 2, quantity: 1, kind: 'BUNDLE_CHILD', lineTotal: 0 },
      ],
    });
    expect(rows).toHaveLength(1);
    expect(rows[0].errors).toEqual(['OUT_OF_STOCK']);
    expect(rows[0].maxQuantity).toBe(1);
    expect(isDiscounted(rows[0])).toBe(true);
    expect(showCredit(rows[0], true)).toBe(true);
    expect(showCredit(rows[0], false)).toBe(false);
    expect(showCredit({ creditUnitPrice: null }, true)).toBe(false);
  });

  test('credit price 0 is still shown', () => {
    expect(showCredit({ creditUnitPrice: 0 }, true)).toBe(true);
  });

  test('subtotal sums lineTotal without float drift', () => {
    expect(
      subtotalOf({ lines: [{ lineTotal: 0.1 }, { lineTotal: 0.2 }, { lineTotal: 1.15 }] }),
    ).toBe(1.45);
    expect(subtotalOf(null)).toBe(0);
  });

  test('fieldPairs drops empty values', () => {
    expect(
      fieldPairs({ a: 'x', b: '', c: true }, { a: 'Name' }, { true: 'Yes', false: 'No' }),
    ).toEqual([
      { label: 'Name', value: 'x' },
      { label: 'c', value: 'Yes' },
    ]);
  });
});

describe('errorKey', () => {
  test('known codes map, unknown fall back', () => {
    expect(errorKey('OUT_OF_STOCK')).toBe('theme.errors.OUT_OF_STOCK');
    expect(errorKey('WHATEVER')).toBe('theme.errors.GENERIC');
  });
});

describe('NavCart', () => {
  test('visibility', () => {
    expect(navVisible(0, '/')).toBe(false);
    expect(navVisible(0, '/store')).toBe(true);
    expect(navVisible(0, '/store/checkout')).toBe(true);
    expect(navVisible(0, '/storefront')).toBe(false);
    expect(navVisible(3, '/blog')).toBe(true);
  });

  test('live cart wins', () => {
    expect(resolveNavCount({ mode: 'GUEST', count: 4, user: null })).toEqual({ count: 4 });
  });

  test('guest reads the local cart, garbage is zero', () => {
    const raw = JSON.stringify({ v: 2, items: [{ productId: 1, variantId: 0, quantity: 3 }] });
    expect(resolveNavCount({ mode: 'NONE', user: null, localRaw: raw }).count).toBe(3);
    expect(resolveNavCount({ mode: 'NONE', user: null, localRaw: '{oops' })).toEqual({ count: 0 });
  });

  test('logged in: fresh cache, stale cache, other user', () => {
    const user = { id: 7 };
    const now = 1_000_000;
    expect(
      resolveNavCount({ mode: 'NONE', user, cacheRaw: countCacheValue(user, 5, now - 1000), now }),
    ).toEqual({ count: 5 });
    expect(
      resolveNavCount({
        mode: 'NONE',
        user,
        cacheRaw: countCacheValue(user, 5, now - 61_000),
        now,
      }),
    ).toEqual({ fetch: true });
    expect(
      resolveNavCount({ mode: 'NONE', user, cacheRaw: countCacheValue({ id: 8 }, 5, now), now }),
    ).toEqual({ fetch: true });
    expect(resolveNavCount({ mode: 'NONE', user, cacheRaw: null, now })).toEqual({ fetch: true });
  });
});

describe('NavCart as a widget', () => {
  test('the offcanvas host is the element CartOffcanvas renders', () => {
    const page = (ids) => ({ getElementById: (id) => (ids.includes(id) ? {} : null) });

    expect(OFFCANVAS_ID).toBe('marketCartOffcanvas');
    expect(hasOffcanvasHost(page([OFFCANVAS_ID]))).toBe(true);
    expect(hasOffcanvasHost(page(['other']))).toBe(false);
    expect(hasOffcanvasHost(undefined)).toBe(false);
    expect(hasOffcanvasHost({})).toBe(false);
  });

  test('checkoutHref: URL map entry, else /store/checkout, site path made absolute with the site URL', () => {
    expect(DEFAULT_CHECKOUT_URL).toBe('/store/checkout');
    expect(checkoutHref(undefined)).toBe('/store/checkout');
    expect(checkoutHref({})).toBe('/store/checkout');
    expect(checkoutHref({ urls: { 'market.checkout': '/shop/checkout' } })).toBe('/shop/checkout');
    expect(checkoutHref({ urls: {}, base: 'https://pano.example.com' })).toBe(
      'https://pano.example.com/store/checkout',
    );
    expect(
      checkoutHref({ urls: { 'market.checkout': '/shop/co' }, base: 'https://pano.example.com/' }),
    ).toBe('https://pano.example.com/shop/co');
    expect(
      checkoutHref({
        urls: { 'market.checkout': 'https://shop.example.org/c' },
        base: 'https://x.y',
      }),
    ).toBe('https://shop.example.org/c');
    expect(
      checkoutHref({ urls: { 'market.checkout': '//cdn.example/c' }, base: 'https://x.y' }),
    ).toBe('//cdn.example/c');
  });
});
