import { describe, expect, test } from 'bun:test';
import { performMutation } from './mutation.js';
import { loadOrderDetailWith } from './load-core.js';
import { NODE } from '../../utils/permissions.js';

function harness(result) {
  const log = [];
  return {
    log,
    deps: {
      send: async (request) => (log.push(['send', request]), result),
      refresh: async () => void log.push(['refresh']),
      success: (body) => log.push(['success', body]),
      failure: (r, stale) => log.push(['failure', r.error, stale]),
    },
  };
}

describe('performMutation (13 §6, §23)', () => {
  test('success: send, refresh, then the toast', async () => {
    const h = harness({ ok: true, body: { created: 1 } });
    const result = await performMutation(h.deps, { method: 'POST', path: '/x' });
    expect(h.log.map((e) => e[0])).toEqual(['send', 'refresh', 'success']);
    expect(h.log[2][1]).toEqual({ created: 1 });
    expect(result.ok).toBe(true);
    expect(result.stale).toBe(false);
  });

  test('stale failure refreshes before reporting', async () => {
    for (const error of ['INVALID_ORDER_TRANSITION', 'INVALID_STATE']) {
      const h = harness({ ok: false, error, body: {} });
      const result = await performMutation(h.deps, {});
      expect(h.log.map((e) => e[0])).toEqual(['send', 'refresh', 'failure']);
      expect(h.log[2]).toEqual(['failure', error, true]);
      expect(result.stale).toBe(true);
    }
  });

  test('other failures never refresh and never report success', async () => {
    for (const error of ['OUT_OF_STOCK', 'NETWORK_ERROR', 'NO_PERMISSION']) {
      const h = harness({ ok: false, error, body: {} });
      const result = await performMutation(h.deps, {});
      expect(h.log.map((e) => e[0])).toEqual(['send', 'failure']);
      expect(result.ok).toBe(false);
      expect(result.stale).toBe(false);
    }
  });
});

describe('loadOrderDetailWith (13 §6)', () => {
  const user = { admin: false, permissions: [NODE.OV] };
  const titles = [];
  const event = (id, parent = { user, pageTitle: { set: (t) => titles.push(t) } }) => ({
    params: { id },
    parent: async () => parent,
  });
  const getter = (map) => ({
    get: async ({ path }) => map[path],
  });
  const API = '';

  test('loads the order and the context in parallel, sets the title', async () => {
    const detail = { order: { id: 5 }, items: [] };
    const out = await loadOrderDetailWith(
      getter({ [`${API}/orders/5`]: detail, [`${API}/context`]: { currency: 'USD' } }),
      event('5'),
    );
    expect(out.data).toEqual({ id: 5, detail, ctx: { currency: 'USD' }, error: null });
    expect(titles.at(-1)).toBe('plugins.pano-plugin-market.pages.order-detail.title');
  });

  test('a non numeric id is NOT_FOUND without any request', async () => {
    let calls = 0;
    const out = await loadOrderDetailWith({ get: async () => (calls++, {}) }, event('abc'));
    expect(out.data.error).toBe('NOT_FOUND');
    expect(out.data.detail).toBeNull();
    expect(calls).toBe(0);
  });

  test('no permission, API errors and a swallowed body are reported, the context is kept', async () => {
    const denied = await loadOrderDetailWith(
      getter({}),
      event('5', { user: { admin: false, permissions: [] } }),
    );
    expect(denied.data.error).toBe('NO_PERMISSION');
    const missing = await loadOrderDetailWith(
      getter({
        [`${API}/orders/5`]: { error: { code: 'NOT_FOUND' } },
        [`${API}/context`]: { a: 1 },
      }),
      event('5'),
    );
    expect(missing.data).toMatchObject({ id: 5, detail: null, error: 'NOT_FOUND', ctx: { a: 1 } });
    const swallowed = await loadOrderDetailWith(
      getter({ [`${API}/orders/5`]: undefined }),
      event('5'),
    );
    expect(swallowed.data).toMatchObject({ error: 'NETWORK_ERROR', ctx: null });
  });

  test('a failed context does not fail the page', async () => {
    const detail = { order: { id: 5 } };
    const out = await loadOrderDetailWith(
      getter({
        [`${API}/orders/5`]: detail,
        [`${API}/context`]: { error: { code: 'NO_PERMISSION' } },
      }),
      event('5'),
    );
    expect(out.data.detail).toBe(detail);
    expect(out.data.ctx).toBeNull();
  });
});
