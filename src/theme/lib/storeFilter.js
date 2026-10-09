// Filter state of the /store page (14 §8.1, §8.2). Pure: no SDK, no browser globals.

export const SORTS = ['priority', 'newest', 'price-asc', 'price-desc', 'bestselling'];
export const DEFAULT_SORT = 'priority';
export const SEARCH_MAX = 64;
export const SEARCH_DEBOUNCE_MS = 300;

export const DEFAULT_FILTER = Object.freeze({
  category: null,
  search: '',
  sort: DEFAULT_SORT,
  page: 1,
});

/** Trimmed, at most 64 characters; one character counts as empty. */
export function normalizeSearch(value) {
  const text = String(value ?? '')
    .trim()
    .slice(0, SEARCH_MAX)
    .trim();

  return text.length < 2 ? '' : text;
}

function positiveInt(value) {
  if (value == null || !/^\d+$/.test(String(value).trim())) return null;
  const n = Number(value);

  return Number.isSafeInteger(n) && n >= 1 ? n : null;
}

/** Reads category / search / sort / page from URLSearchParams. The category is not validated here. */
export function parseFilter(params) {
  const get = (name) => (params && typeof params.get === 'function' ? params.get(name) : null);
  const sort = get('sort');

  return {
    category: positiveInt(get('category')),
    search: normalizeSearch(get('search')),
    sort: SORTS.includes(sort) ? sort : DEFAULT_SORT,
    page: positiveInt(get('page')) ?? 1,
  };
}

/** ISO currency code of ?currency=, or null (whether the store offers it is checked later). */
export function parseCurrency(params) {
  const value = params && typeof params.get === 'function' ? params.get('currency') : null;

  return typeof value === 'string' && /^[A-Z]{3}$/.test(value) ? value : null;
}

export function isDefaultFilter(filter) {
  return (
    filter.category == null &&
    !filter.search &&
    filter.sort === DEFAULT_SORT &&
    (filter.page ?? 1) === 1
  );
}

/** Set of every category id of the tree. */
export function categoryIds(tree) {
  const ids = new Set();
  const walk = (nodes) => {
    for (const node of nodes || []) {
      ids.add(node.id);
      walk(node.children);
    }
  };
  walk(tree);

  return ids;
}

/** The tree flattened depth first: [{ id, name, icon, color, productsCount, depth }]. */
export function flattenCategories(tree) {
  const rows = [];
  const walk = (nodes, depth) => {
    for (const node of nodes || []) {
      rows.push({
        id: node.id,
        name: node.name,
        icon: node.icon || null,
        color: node.color || null,
        productsCount: node.productsCount ?? 0,
        depth,
      });
      walk(node.children, depth + 1);
    }
  };
  walk(tree, 0);

  return rows;
}

/** Indent utility of a row: depth 0 none, 1 ps-3, 2 ps-4, 3 and deeper ps-5 (no inline padding). */
export function indentClass(depth) {
  if (depth <= 0) return '';
  if (depth === 1) return 'ps-3';
  if (depth === 2) return 'ps-4';

  return 'ps-5';
}

/** Canonical query: only non-default values, in the order category, search, sort, page. '' when none. */
export function canonicalQuery(filter) {
  const pairs = [];
  if (filter.category != null) pairs.push(['category', String(filter.category)]);
  if (filter.search) pairs.push(['search', filter.search]);
  if (filter.sort && filter.sort !== DEFAULT_SORT) pairs.push(['sort', filter.sort]);
  if ((filter.page ?? 1) > 1) pairs.push(['page', String(filter.page)]);

  return pairs.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
}

/** Query object of GET /store/products. Empty values stay out (the api wrapper skips them). */
export function listQuery(filter, currency) {
  return {
    category: filter.category ?? undefined,
    search: filter.search || undefined,
    sort: filter.sort || DEFAULT_SORT,
    page: filter.page ?? 1,
    currency: currency || undefined,
  };
}

/** A new filter after one control changed. Category, sort and search changes reset the page to 1. */
export function withChange(filter, change) {
  const next = { ...filter, ...change };
  if ('category' in change || 'sort' in change || 'search' in change) next.page = change.page ?? 1;
  if ('search' in change) next.search = normalizeSearch(change.search);

  return next;
}

/** True when a response of request `seq` is still the latest one. */
export function isLatest(seq, latest) {
  return seq === latest;
}

/**
 * Request sequencing of the /store page. Two counters: `grid` guards only the product grid (every filter
 * change and every reload takes a ticket), `store` guards the store part of a reload (settings, trees,
 * cards, first page). A filter change therefore never discards a currency reload's store refresh.
 */
export function createSequencer() {
  let grid = 0;
  let store = 0;

  return {
    /** Ticket of a filter change: supersedes every older grid request. */
    beginGrid() {
      return ++grid;
    },
    /** Tickets of a full reload: supersedes older grid requests and older reloads. */
    beginReload() {
      return { grid: ++grid, store: ++store };
    },
    isGridLatest(ticket) {
      return isLatest(ticket, grid);
    },
    isStoreLatest(ticket) {
      return isLatest(ticket, store);
    },
    /** Drops everything in flight (unmount). */
    invalidate() {
      grid++;
      store++;
    },
  };
}

/** Query string (without '?') of the address bar: the canonical filter plus ?currency= only while it is pinned by the URL. */
export function storeSearch(filter, urlCurrency) {
  const currency = urlCurrency ? `currency=${encodeURIComponent(urlCurrency)}` : '';

  return [canonicalQuery(filter), currency].filter(Boolean).join('&');
}

/**
 * Pager items: previous, 1, gap, window of +-2 around the current page, gap, last, next.
 * Items: { type: 'prev'|'next', page, disabled } | { type: 'page', page, current } | { type: 'gap' }.
 * A gap that would hide a single page shows that page instead.
 */
export function pagerItems(current, totalPages, radius = 2) {
  const total = Math.max(1, Math.floor(Number(totalPages) || 1));
  const page = Math.min(total, Math.max(1, Math.floor(Number(current) || 1)));
  const from = Math.max(1, page - radius);
  const to = Math.min(total, page + radius);
  const numbers = [];

  const push = (n) => numbers.push({ type: 'page', page: n, current: n === page });

  if (from > 1) {
    push(1);
    if (from === 3) push(2);
    else if (from > 3) numbers.push({ type: 'gap' });
  }
  for (let n = from; n <= to; n++) push(n);
  if (to < total) {
    if (to === total - 2) push(total - 1);
    else if (to < total - 2) numbers.push({ type: 'gap' });
    push(total);
  }

  return [
    { type: 'prev', page: page - 1, disabled: page <= 1 },
    ...numbers,
    { type: 'next', page: page + 1, disabled: page >= total },
  ];
}

/** `href` without its `page` parameter (the target of the PAGE_NOT_FOUND redirect). */
export function withoutPageParam(href) {
  const url = new URL(href, 'http://localhost');
  url.searchParams.delete('page');
  const query = url.searchParams.toString();

  return `${url.pathname}${query ? `?${query}` : ''}${url.hash}`;
}
