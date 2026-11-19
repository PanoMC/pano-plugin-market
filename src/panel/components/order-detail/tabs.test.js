import { describe, expect, test } from 'bun:test';
import {
  attentionItems,
  initialTab,
  latestPayment,
  tabCounts,
  tabHref,
  tabsNeedingAttention,
} from './tabs.js';

describe('initialTab', () => {
  test('reads ?tab=', () => {
    expect(initialTab('?tab=payments', '')).toBe('payments');
    expect(initialTab('?tab=history&x=1', '')).toBe('history');
  });
  test('unknown or missing is overview', () => {
    expect(initialTab('?tab=nope', '')).toBe('overview');
    expect(initialTab('', '')).toBe('overview');
    expect(initialTab(undefined, undefined)).toBe('overview');
  });
  test('#deliveries opens fulfillment', () => {
    expect(initialTab('?tab=payments', '#deliveries')).toBe('fulfillment');
  });
});

describe('tabHref', () => {
  test('sets and drops the param, keeps others', () => {
    expect(tabHref('http://x/panel/market/orders/detail/7?a=1#deliveries', 'payments')).toBe(
      '/panel/market/orders/detail/7?a=1&tab=payments',
    );
    expect(tabHref('http://x/p/7?tab=payments', 'overview')).toBe('/p/7');
  });
});

const detail = {
  order: { requiresShipping: true },
  payments: [
    { id: 4, status: 'FAILED' },
    { id: 9, status: 'REVIEW' },
  ],
  refunds: [{ id: 1, status: 'FAILED' }],
  disputes: [
    { id: 1, status: 'OPEN' },
    { id: 2, status: 'WON' },
  ],
  deliveries: [{ status: 'FAILED' }, { status: 'CONFIRMED' }],
  shipments: [{ status: 'LOST' }],
  mails: [{ status: 'FAILED' }, { status: 'SENT' }],
  events: [{}, {}, {}],
  revokePending: 1,
  revokeFailed: 0,
};

describe('tabCounts', () => {
  test('sums the cards of each tab', () => {
    expect(tabCounts(detail, { shipping: true })).toEqual({
      overview: null,
      payments: 5,
      fulfillment: 3,
      history: 5,
    });
  });
  test('shipments count only with shipping on', () => {
    expect(tabCounts(detail).fulfillment).toBe(2);
  });
  test('empty detail', () => {
    expect(tabCounts(null)).toEqual({ overview: null, payments: 0, fulfillment: 0, history: 0 });
  });
});

describe('attentionItems', () => {
  test('lists every problem with its tab', () => {
    const items = attentionItems(detail, { shipping: true });
    expect(items.map((i) => [i.key, i.tab, i.count])).toEqual([
      ['revoke', 'fulfillment', 1],
      ['delivery-failed', 'fulfillment', 1],
      ['shipment-problem', 'fulfillment', 1],
      ['dispute-open', 'payments', 1],
      ['refund-failed', 'payments', 1],
      ['payment-review', 'payments', 1],
      ['mail-failed', 'history', 1],
    ]);
    expect(items[0].values).toEqual({ pending: 1, failed: 0 });
    expect(items[0].danger).toBe(true);
    expect([...tabsNeedingAttention(items)].sort()).toEqual(['fulfillment', 'history', 'payments']);
  });
  test('a calm order has none', () => {
    expect(attentionItems({ order: {}, deliveries: [{ status: 'CONFIRMED' }] })).toEqual([]);
    expect(attentionItems(null)).toEqual([]);
  });
});

describe('latestPayment', () => {
  test('highest id', () => {
    expect(latestPayment(detail.payments).id).toBe(9);
    expect(latestPayment([])).toBe(null);
  });
});
