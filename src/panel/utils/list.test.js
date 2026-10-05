import { beforeEach, describe, expect, test } from 'bun:test';
import { loadContextWith, loadListWith } from './list-core.js';

const requests = [];
let responder = () => ({});

const buildQueryParams = (params) => {
  const qs = Object.keys(params)
    .filter((k) => params[k])
    .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(params[k])}`)
    .join('&');
  return qs === '' ? '' : '?' + qs;
};
const deps = {
  get: async ({ path }) => {
    requests.push(path);
    return responder(path);
  },
  buildQueryParams,
};
const loadList = (event, options) => loadListWith(deps, event, options);
const loadContext = (event) => loadContextWith(deps, event);

const titles = [];
const event = (query = '', user = { admin: true }) => ({
  url: new URL('http://x/market/orders' + query),
  parent: async () => ({ user, pageTitle: { set: (v) => titles.push(v) } }),
});
const opts = { path: '/orders', params: ['search', 'status'], nodes: ['OV'], emptyKey: 'orders' };
const CTX = { currency: 'USD' };

beforeEach(() => {
  requests.length = 0;
  titles.length = 0;
  responder = (path) =>
    path.endsWith('/context') ? CTX : { orders: [{ id: 1 }], count: 1, totalPage: 1 };
});

describe('loadList', () => {
  test('starts with the can() guard: no request without the node', async () => {
    const { data } = await loadList(event('', { admin: false, permissions: [] }), opts);
    expect(data.error).toBe('NO_PERMISSION');
    expect(data.orders).toEqual([]);
    expect(requests).toEqual([]);
  });

  test('fetches the list and the context in parallel, with filters and without page 1', async () => {
    const { data } = await loadList(event('?search=steve&status=PENDING&page=1'), {
      ...opts,
      title: 'pages.orders.title',
    });
    expect(requests.sort()).toEqual([
      '/api/panel/market/context',
      '/api/panel/market/orders?search=steve&status=PENDING',
    ]);
    expect(data.orders).toEqual([{ id: 1 }]);
    expect(data.page).toBe(1);
    expect(data.ctx).toEqual(CTX);
    expect(data.filters).toEqual({ search: 'steve', status: 'PENDING' });
    expect(titles).toEqual(['plugins.pano-plugin-market.pages.orders.title']);
  });

  test('page > 1 is sent', async () => {
    await loadList(event('?page=3'), opts);
    expect(requests).toContain('/api/panel/market/orders?page=3');
  });

  test('a stale page falls back to page 1 once', async () => {
    responder = (path) => {
      if (path.endsWith('/context')) return CTX;
      return path.includes('page=5')
        ? { error: 'PAGE_NOT_FOUND' }
        : { orders: [], count: 0, totalPage: 1 };
    };
    const { data } = await loadList(event('?page=5&search=a'), opts);
    expect(data.page).toBe(1);
    expect(data.error).toBeUndefined();
    expect(requests.filter((r) => r.includes('/orders'))).toEqual([
      '/api/panel/market/orders?search=a&page=5',
      '/api/panel/market/orders?search=a',
    ]);
  });

  test('PAGE_NOT_FOUND on page 1 is an error, not a loop', async () => {
    responder = (path) => (path.endsWith('/context') ? CTX : { error: 'PAGE_NOT_FOUND' });
    const { data } = await loadList(event(''), opts);
    expect(data.error).toBe('PAGE_NOT_FOUND');
    expect(requests.filter((r) => r.includes('/orders'))).toHaveLength(1);
  });

  test('failure gives the empty shape with the error and keeps the context', async () => {
    responder = (path) => (path.endsWith('/context') ? CTX : { error: 'NOT_FOUND' });
    const { data } = await loadList(event('?status=X'), opts);
    expect(data).toEqual({
      orders: [],
      count: 0,
      totalPage: 1,
      page: 1,
      error: 'NOT_FOUND',
      ctx: CTX,
      filters: { search: null, status: 'X' },
    });
  });

  test('a swallowed (falsy) body is NETWORK_ERROR', async () => {
    responder = (path) => (path.endsWith('/context') ? null : undefined);
    const { data } = await loadList(event(''), opts);
    expect(data.error).toBe('NETWORK_ERROR');
    expect(data.ctx).toBeNull();
  });
});

describe('loadContext', () => {
  test('loadContext returns null on an error body', async () => {
    responder = () => ({ error: 'NO_PERMISSION' });
    expect(await loadContext(event(''))).toBeNull();
    responder = () => CTX;
    expect(await loadContext(event(''))).toEqual(CTX);
  });
});
