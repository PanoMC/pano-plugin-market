import { describe, expect, test } from 'bun:test';
import { hasFilter, resolveStoreLoad, validateFilter } from '../storeLoad.js';
import { DEFAULT_FILTER } from '../storeFilter.js';

const origin = 'https://example.com';
const card = (id) => ({ id, slug: `p${id}`, name: `P${id}`, price: 1, inStock: true });
const store = {
  ok: true,
  settings: { storeName: 'Shop', storeDescription: 'Desc' },
  categories: [{ id: 1, name: 'A', children: [{ id: 2, name: 'B' }] }],
  items: [card(1), card(2)],
  page: { number: 1, size: 10, totalItems: 25, totalPages: 3 },
  featured: [card(1)],
  bestsellers: [card(2)],
  comparisons: [{ id: 9 }],
  comparisonProducts: [card(3)],
};

describe('store failure states', () => {
  test('STORE_DISABLED => DISABLED', () => {
    const res = resolveStoreLoad({
      store: { ok: false, code: 'STORE_DISABLED' },
      filter: DEFAULT_FILTER,
      origin,
    });
    expect(res.data).toEqual({ state: 'DISABLED' });
    expect(res.pageTitle.title).toBe('plugins.pano-plugin-market.theme.store.title');
  });

  test('STORE_UNAVAILABLE, NETWORK and others => ERROR with the code, never an empty grid', () => {
    for (const code of ['STORE_UNAVAILABLE', 'NETWORK', 'SOMETHING']) {
      const res = resolveStoreLoad({ store: { ok: false, code }, filter: DEFAULT_FILTER, origin });
      expect(res.data).toEqual({ state: 'ERROR', code });
      expect(res.data.grid).toBeUndefined();
    }
  });

  test('a missing result is a NETWORK error', () => {
    expect(resolveStoreLoad({ store: undefined, filter: DEFAULT_FILTER, origin }).data).toEqual({
      state: 'ERROR',
      code: 'NETWORK',
    });
  });

  test('no meta on a failed load', () => {
    expect(
      resolveStoreLoad({
        store: { ok: false, code: 'NETWORK' },
        filter: DEFAULT_FILTER,
        origin,
        withMeta: true,
      }).meta,
    ).toBeUndefined();
  });
});

describe('ready state', () => {
  test('without a list the grid is the first page of the store response', () => {
    const res = resolveStoreLoad({
      store,
      list: null,
      widgets: null,
      filter: DEFAULT_FILTER,
      origin,
    });
    expect(res.data.state).toBe('READY');
    expect(res.data.grid).toEqual({
      state: 'READY',
      products: store.items,
      productCount: 25,
      totalPages: 3,
    });
    expect(res.data.firstPage.products).toEqual(store.items);
    expect(res.data.totalCount).toBe(25);
    expect(res.data.featured).toEqual(store.featured);
    expect(res.data.comparisonProducts).toEqual(store.comparisonProducts);
    expect(res.data.widgets).toEqual({});
    expect(res.pageTitle.titleValues).toEqual({ storeName: 'Shop' });
  });

  test('a successful list replaces the grid, the first page stays', () => {
    const list = {
      ok: true,
      items: [card(7)],
      page: { number: 1, size: 10, totalItems: 1, totalPages: 1 },
    };
    const res = resolveStoreLoad({
      store,
      list,
      filter: { ...DEFAULT_FILTER, search: 'ab' },
      origin,
    });
    expect(res.data.grid.products).toEqual([card(7)]);
    expect(res.data.grid.productCount).toBe(1);
    expect(res.data.firstPage.products).toEqual(store.items);
    expect(res.data.filter.search).toBe('ab');
  });

  test('PAGE_NOT_FOUND of the list => redirect', () => {
    const res = resolveStoreLoad({
      store,
      list: { ok: false, code: 'PAGE_NOT_FOUND' },
      filter: { ...DEFAULT_FILTER, page: 9 },
      origin,
    });
    expect(res).toEqual({ redirect: 'page' });
  });

  test('another list failure => grid ERROR, the rest of the page still renders', () => {
    const res = resolveStoreLoad({
      store,
      list: { ok: false, code: 'NETWORK' },
      filter: { ...DEFAULT_FILTER, search: 'ab' },
      origin,
    });
    expect(res.data.state).toBe('READY');
    expect(res.data.grid.state).toBe('ERROR');
    expect(res.data.featured).toHaveLength(1);
  });

  test('widgets: failure => {}, success => the payload without the envelope', () => {
    const bad = resolveStoreLoad({
      store,
      widgets: { ok: false, code: 'NETWORK' },
      filter: DEFAULT_FILTER,
      origin,
    });
    expect(bad.data.widgets).toEqual({});
    const good = resolveStoreLoad({
      store,
      widgets: { ok: true, result: 'ok', goals: [{ id: 1 }], sidebars: ['home'] },
      filter: DEFAULT_FILTER,
      origin,
    });
    expect(good.data.widgets).toEqual({ goals: [{ id: 1 }], sidebars: ['home'] });
  });

  test('meta only on request', () => {
    expect(resolveStoreLoad({ store, filter: DEFAULT_FILTER, origin }).meta).toBeUndefined();
    const res = resolveStoreLoad({ store, filter: DEFAULT_FILTER, origin, withMeta: true });
    expect(res.meta.canonical).toBe('https://example.com/store');
    expect(res.meta.description).toBe('Desc');
  });

  test('an unknown category is dropped from the filter and the canonical', () => {
    const res = resolveStoreLoad({
      store,
      filter: { ...DEFAULT_FILTER, category: 99 },
      origin,
      withMeta: true,
    });
    expect(res.data.filter.category).toBe(null);
    expect(res.meta.canonical).toBe('https://example.com/store');
  });
});

describe('filter helpers', () => {
  test('validateFilter keeps known (also nested) categories and returns the same object', () => {
    const filter = { ...DEFAULT_FILTER, category: 2 };
    expect(validateFilter(filter, store.categories)).toBe(filter);
    expect(validateFilter({ ...DEFAULT_FILTER, category: 99 }, store.categories).category).toBe(
      null,
    );
  });

  test('hasFilter', () => {
    expect(hasFilter(DEFAULT_FILTER)).toBe(false);
    expect(hasFilter({ ...DEFAULT_FILTER, page: 2 })).toBe(true);
    expect(hasFilter({ ...DEFAULT_FILTER, category: 2 })).toBe(true);
  });
});
