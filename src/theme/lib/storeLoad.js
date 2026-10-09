// Result mapping of the /store load (14 §8.1). Pure: the page module does the requests and feeds the
// normalised ApiResults (`{ ok: true, ... }` | `{ ok: false, code }`) in here.
import { readList } from './api-result.js';
import { categoryIds, isDefaultFilter } from './storeFilter.js';
import { storeMeta, storePageTitle } from './storeMeta.js';

const PLAIN_TITLE = { title: 'plugins.pano-plugin-market.theme.store.title' };

/** True when the URL carries any of category / search / sort / page > 1 (the products list is then fetched). */
export function hasFilter(filter) {
  return !isDefaultFilter(filter);
}

/** The filter with a category that is not in the tree dropped (honoured only if it exists). */
export function validateFilter(filter, categories) {
  if (filter.category != null && !categoryIds(categories).has(filter.category))
    return { ...filter, category: null };

  return filter;
}

/** Grid of a product list answer (`{ items, page }` of `/store` or `/store/products`). */
export function gridOf(res) {
  const list = readList(res);

  return {
    state: 'READY',
    products: list.items,
    productCount: list.totalItems,
    totalPages: list.totalPages,
  };
}

/** Grid of a failed / absent list: the first page the store response carried. */
const firstPage = gridOf;

/** The `data` + `pageTitle` (+ `meta`) of a load, or `{ redirect: 'page' }` for a PAGE_NOT_FOUND list. */
export function resolveStoreLoad({ store, list, widgets, filter, origin, withMeta = false }) {
  if (!store || !store.ok) {
    const code = store?.code || 'NETWORK';

    if (code === 'STORE_DISABLED') return { data: { state: 'DISABLED' }, pageTitle: PLAIN_TITLE };

    return { data: { state: 'ERROR', code }, pageTitle: PLAIN_TITLE };
  }

  const settings = store.settings || {};
  const categories = store.categories || [];
  const valid = validateFilter(filter, categories);

  let grid;
  if (list == null) {
    grid = firstPage(store);
  } else if (list.ok) {
    grid = gridOf(list);
  } else if (list.code === 'PAGE_NOT_FOUND') {
    return { redirect: 'page' };
  } else {
    grid = {
      state: 'ERROR',
      code: list.code || 'NETWORK',
      products: [],
      productCount: 0,
      totalPages: 1,
    };
  }

  const result = {
    data: {
      state: 'READY',
      settings,
      categories,
      grid,
      totalCount: firstPage(store).productCount,
      firstPage: firstPage(store),
      featured: store.featured || [],
      bestsellers: store.bestsellers || [],
      comparisons: store.comparisons || [],
      comparisonProducts: store.comparisonProducts || [],
      widgets: widgets && widgets.ok ? stripEnvelope(widgets) : {},
      filter: valid,
    },
    pageTitle: storePageTitle(settings),
  };

  if (withMeta) result.meta = storeMeta({ settings, filter: valid, origin });

  return result;
}

function stripEnvelope(res) {
  const { ok, result, ...rest } = res; // eslint-disable-line no-unused-vars

  return rest;
}
