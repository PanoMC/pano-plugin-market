import { describe, expect, test } from 'bun:test';
import { NODE } from '../../utils/permissions.js';
import {
  blocksLink,
  buildSummaryView,
  cardVisible,
  createOrderLink,
  loadPlayerSummary,
  ordersLink,
  summaryPath,
} from './summary.js';

const fmt = {
  money: (v, c) => `${Number(v).toFixed(2)} ${c}`,
  credits: (v, n) => `${v} ${n}`.trim(),
};
const admin = { permissions: [NODE.ALL] };
const viewer = { permissions: [NODE.OV] };

const summary = (extra = {}) => ({
  user: { id: 7, username: 'Steve' },
  creditBalance: 250,
  totals: { orders: 3, spent: 40, refunded: 5, currency: 'USD' },
  orders: [{ id: 1 }],
  entitlements: [],
  subscriptions: [],
  blocks: [],
  ...extra,
});

const eventFor = (user, username = 'Steve') => ({
  params: { username },
  parent: async () => ({ user, pageTitle: { set() {} } }),
});

describe('player summary view model', () => {
  test('four stat cards with the spec classes and formatted values', () => {
    const v = buildSummaryView({
      summary: summary(),
      username: 'Steve',
      ctx: { creditName: 'Coins' },
      user: admin,
      fmt,
    });
    expect(v.stats.map((s) => [s.key, s.cls])).toEqual([
      ['orders', 'text-bg-primary'],
      ['spent', 'text-bg-success'],
      ['refunded', 'text-bg-warning'],
      ['credits', 'text-bg-info'],
    ]);
    expect(v.stats.map((s) => s.value)).toEqual(['3', '40.00 USD', '5.00 USD', '250 Coins']);
    expect(cardVisible(v)).toBe(true);
  });

  test('user null: credits show a dash and the credit actions are hidden', () => {
    const v = buildSummaryView({
      summary: summary({ user: null }),
      username: 'Ghost',
      user: admin,
      fmt,
    });
    expect(v.stats[3].value).toBe('—');
    expect(v.actions.grant).toBe(false);
    expect(v.actions.revoke).toBe(false);
    expect(v.actions.createOrder).toBe(true);
    expect(v.account).toBeNull();
  });

  test('actions follow the permission nodes', () => {
    const v = buildSummaryView({ summary: summary(), username: 'Steve', user: viewer, fmt });
    expect(v.actions).toEqual({
      allOrders: true,
      grant: false,
      revoke: false,
      createOrder: false,
      block: false,
    });
    const a = buildSummaryView({ summary: summary(), username: 'Steve', user: admin, fmt });
    expect(Object.values(a.actions).every(Boolean)).toBe(true);
    expect(a.account).toEqual({ userId: 7, username: 'Steve', balance: 250 });
  });

  test('a hook-load error renders nothing', () => {
    const v = buildSummaryView({
      summary: null,
      username: 'Steve',
      error: 'NETWORK_ERROR',
      user: admin,
      fmt,
    });
    expect(v.error).toBe('NETWORK_ERROR');
    expect(cardVisible(v)).toBe(false);
    expect(cardVisible(buildSummaryView({ summary: summary(), error: 'NOT_FOUND', fmt }))).toBe(
      false,
    );
  });

  test('missing or malformed fields never throw', () => {
    const v = buildSummaryView({
      summary: { user: null, orders: 'x' },
      username: 'A',
      user: admin,
      fmt,
    });
    expect(v.orders).toEqual([]);
    expect(v.entitlements).toEqual([]);
    expect(v.stats[0].value).toBe('0');
    expect(cardVisible(buildSummaryView({ summary: 'oops', fmt }))).toBe(false);
  });

  test('links encode the username', () => {
    expect(ordersLink('a b&c')).toBe('/market/orders?search=a%20b%26c');
    expect(createOrderLink('a b')).toBe('/market/orders/create-order?player=a%20b');
    expect(blocksLink('x')).toBe('/market/blocks?search=x');
    expect(summaryPath('a/b')).toContain('/players/a%2Fb/summary');
  });
});

describe('loadPlayerSummary', () => {
  const ok = { get: async () => summary(), loadContext: async () => ({ creditName: 'C' }) };

  test('loads summary and context', async () => {
    const r = await loadPlayerSummary(ok, eventFor(admin));
    expect(r.data.error).toBeNull();
    expect(r.data.summary.creditBalance).toBe(250);
    expect(r.data.ctx.creditName).toBe('C');
    expect(r.data.username).toBe('Steve');
  });

  test('no permission: no request is made', async () => {
    let called = false;
    const deps = { get: async () => ((called = true), summary()), loadContext: async () => null };
    const r = await loadPlayerSummary(deps, eventFor({ permissions: [] }));
    expect(r.data.error).toBe('NO_PERMISSION');
    expect(called).toBe(false);
  });

  test('api error, falsy body and thrown errors become data.error', async () => {
    const mk = (get) => ({ get, loadContext: async () => null });
    expect(
      (
        await loadPlayerSummary(
          mk(async () => ({ error: { code: 'NO_PERMISSION' } })),
          eventFor(admin),
        )
      ).data.error,
    ).toBe('NO_PERMISSION');
    expect(
      (
        await loadPlayerSummary(
          mk(async () => null),
          eventFor(admin),
        )
      ).data.error,
    ).toBe('NETWORK_ERROR');
    expect(
      (
        await loadPlayerSummary(
          mk(async () => {
            throw new Error('x');
          }),
          eventFor(admin),
        )
      ).data.error,
    ).toBe('NETWORK_ERROR');
  });
});
