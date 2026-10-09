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
const opts = { path: '/orders', params: ['search', 'status'], nodes: ['OV'] };
const CTX = { currency: 'USD' };
const page = (number, totalItems, size = 10) => ({
  number,
  size,
  totalItems,
  totalPages: Math.ceil(totalItems / size),
});
const PAGE_OF_ONE = { items: [{ id: 1 }], page: page(1, 1) };
const EMPTY_PAGE = { number: 1, size: 0, totalItems: 0, totalPages: 0 };

beforeEach(() => {
  requests.length = 0;
  titles.length = 0;
  responder = (path) => (path.endsWith('/context') ? CTX : PAGE_OF_ONE);
});

describe('loadList', () => {
  test('starts with the can() guard: no request without the node', async () => {
    const { data } = await loadList(event('', { admin: false, permissions: [] }), opts);
    expect(data.error).toBe('NO_PERMISSION');
    expect(data.items).toEqual([]);
    expect(requests).toEqual([]);
  });

  test('fetches the list and the context in parallel, with filters and without page 1', async () => {
    const { data } = await loadList(event('?search=steve&status=PENDING&page=1'), {
      ...opts,
      title: 'pages.orders.title',
    });
    expect(requests.sort()).toEqual(['/context', '/orders?search=steve&status=PENDING']);
    expect(data.items).toEqual([{ id: 1 }]);
    expect(data.page).toEqual(page(1, 1));
    expect(data.ctx).toEqual(CTX);
    expect(data.filters).toEqual({ search: 'steve', status: 'PENDING' });
    expect(titles).toEqual(['plugins.pano-plugin-market.pages.orders.title']);
  });

  test('page > 1 is sent', async () => {
    await loadList(event('?page=3'), opts);
    expect(requests).toContain('/orders?page=3');
  });

  test('a stale page falls back to page 1 once', async () => {
    responder = (path) => {
      if (path.endsWith('/context')) return CTX;
      return path.includes('page=5')
        ? { error: { code: 'PAGE_NOT_FOUND' } }
        : { items: [], page: page(1, 0) };
    };
    const { data } = await loadList(event('?page=5&search=a'), opts);
    expect(data.page.number).toBe(1);
    expect(data.error).toBeUndefined();
    expect(requests.filter((r) => r.startsWith('/orders'))).toEqual([
      '/orders?search=a&page=5',
      '/orders?search=a',
    ]);
  });

  test('PAGE_NOT_FOUND on page 1 is an error, not a loop', async () => {
    responder = (path) => (path.endsWith('/context') ? CTX : { error: { code: 'PAGE_NOT_FOUND' } });
    const { data } = await loadList(event(''), opts);
    expect(data.error).toBe('PAGE_NOT_FOUND');
    expect(requests.filter((r) => r.startsWith('/orders'))).toHaveLength(1);
  });

  test('failure gives the empty shape with the error and keeps the context', async () => {
    responder = (path) =>
      path.endsWith('/context') ? CTX : { error: { code: 'NOT_FOUND', message: 'x' } };
    const { data } = await loadList(event('?status=X'), opts);
    expect(data).toEqual({
      items: [],
      page: EMPTY_PAGE,
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

  test('a non-JSON (string) body is NETWORK_ERROR with the empty shape, never spread into data', async () => {
    responder = (path) => (path.endsWith('/context') ? '<html>502</html>' : '<html>502</html>');
    const { data } = await loadList(event('?status=X'), opts);
    expect(data).toEqual({
      items: [],
      page: EMPTY_PAGE,
      error: 'NETWORK_ERROR',
      ctx: null,
      filters: { search: null, status: 'X' },
    });
  });
});

describe('loadContext', () => {
  test('loadContext returns null on a string body', async () => {
    responder = () => '<html>502</html>';
    expect(await loadContext(event(''))).toBeNull();
  });

  test('loadContext returns null on an error body', async () => {
    responder = () => ({ error: { code: 'NO_PERMISSION' } });
    expect(await loadContext(event(''))).toBeNull();
    responder = () => CTX;
    expect(await loadContext(event(''))).toEqual(CTX);
  });
});
