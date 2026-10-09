import { beforeEach, describe, expect, test } from 'bun:test';
import { loadOverviewWith } from './load.js';

const qs = (params) => {
  const parts = Object.keys(params)
    .filter((k) => params[k] !== null && params[k] !== undefined)
    .map((k) => `${k}=${params[k]}`);
  return parts.length ? '?' + parts.join('&') : '';
};
const requests = [];
let responder;
const deps = {
  get: async ({ path }) => {
    requests.push(path);
    return responder(path);
  },
  buildQueryParams: qs,
};
const titles = [];
const event = (query, user) => ({
  url: new URL('http://x/market' + query),
  parent: async () => ({ user, pageTitle: { set: (v) => titles.push(v) } }),
});
const NOW = new Date(2026, 9, 15, 12).getTime();
const pageOf = (items, totalItems) => ({
  items,
  page: { number: 1, size: items.length, totalItems, totalPages: 1 },
});

beforeEach(() => {
  requests.length = 0;
  titles.length = 0;
  responder = (path) => {
    if (path.includes('/context')) return { currency: 'USD' };
    if (path.includes('/stats')) return { summary: {}, charts: {} };
    if (path.includes('status=REVIEW')) return pageOf([{ id: 1 }], 4);
    if (path.includes('/orders')) return pageOf([{ id: 2 }, { id: 3 }], 2);
    if (path.includes('/servers')) return { items: [{ id: 1, name: 'S' }] };
    if (path.includes('/health')) return { runtimeState: 'READY' };
    return {};
  };
});

describe('loadOverviewWith', () => {
  test('admin: every block is requested', async () => {
    const { data } = await loadOverviewWith(deps, event('', { admin: true }), NOW);
    expect(requests.some((p) => p.includes('/stats?from='))).toBe(true);
    expect(requests).toContain('/orders?pageSize=10');
    expect(requests).toContain('/orders?status=REVIEW&pageSize=1');
    expect(requests).toContain('/servers');
    expect(requests).toContain('/health');
    expect(data.reviewCount).toBe(4);
    expect(data.orders).toHaveLength(2);
    expect(data.servers).toHaveLength(1);
    expect(data.health.runtimeState).toBe('READY');
    expect(data.range).toBe('30d');
    expect(titles).toEqual(['plugins.pano-plugin-market.pages.overview.title']);
  });

  test('the range is sent to /stats', async () => {
    await loadOverviewWith(deps, event('?from=1000&to=2000', { admin: true }), NOW);
    expect(requests).toContain('/stats?from=1000&to=2000');
  });

  test('chart view does not load the recent orders', async () => {
    const { data } = await loadOverviewWith(deps, event('?view=chart', { admin: true }), NOW);
    expect(data.view).toBe('chart');
    expect(requests).not.toContain('/orders?pageSize=10');
    expect(requests).toContain('/orders?status=REVIEW&pageSize=1');
    expect(data.ordersError).toBeNull();
  });

  test('only STATS: no orders, no health, servers still loaded', async () => {
    const user = { permissions: ['pano.plugin.pano-plugin-market.view.market.stats'] };
    const { data } = await loadOverviewWith(deps, event('', user), NOW);
    expect(requests.some((p) => p.includes('/orders'))).toBe(false);
    expect(requests.some((p) => p.includes('/health'))).toBe(false);
    expect(requests.some((p) => p.includes('/stats'))).toBe(true);
    expect(requests.some((p) => p.includes('/servers'))).toBe(true);
    expect(data.canOrders).toBe(false);
    expect(data.health).toBeNull();
  });

  test('only OV: no stats, no health', async () => {
    const user = { permissions: ['pano.plugin.pano-plugin-market.view.market.orders'] };
    const { data } = await loadOverviewWith(deps, event('', user), NOW);
    expect(requests.some((p) => p.includes('/stats'))).toBe(false);
    expect(requests.some((p) => p.includes('/health'))).toBe(false);
    expect(data.statsError).toBeNull();
  });

  test('no node at all: NO_PERMISSION and no request', async () => {
    const result = await loadOverviewWith(deps, event('', { permissions: [] }), NOW);
    expect(result).toEqual({ data: { error: 'NO_PERMISSION' } });
    expect(requests).toEqual([]);
  });

  test('each failure degrades its own block only', async () => {
    responder = (path) => {
      if (path.includes('/stats')) return { error: { code: 'STORE_BUSY' } };
      if (path.includes('/health')) return 'Bad Gateway';
      if (path.includes('status=REVIEW')) return { error: { code: 'X' } };
      if (path.includes('/orders')) return pageOf([{ id: 2 }], 1);
      if (path.includes('/servers')) return { items: [{ id: 1, name: 'S' }] };
      if (path.includes('/context')) return { currency: 'USD' };
      return {};
    };
    const { data } = await loadOverviewWith(deps, event('', { admin: true }), NOW);
    expect(data.stats).toBeNull();
    expect(data.statsError).toBe('STORE_BUSY');
    expect(data.health).toBeNull();
    expect(data.reviewCount).toBe(0);
    expect(data.orders).toHaveLength(1);
    expect(data.servers).toHaveLength(1);
    expect(data.ctx).toEqual({ currency: 'USD' });
  });

  test('a non-JSON reply is a network error', async () => {
    responder = () => 'Bad Gateway';
    const { data } = await loadOverviewWith(deps, event('', { admin: true }), NOW);
    expect(data.statsError).toBe('NETWORK_ERROR');
    expect(data.ordersError).toBe('NETWORK_ERROR');
    expect(data.ctx).toBeNull();
    expect(data.servers).toEqual([]);
  });
});
