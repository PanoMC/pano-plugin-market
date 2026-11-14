import { describe, expect, test } from 'bun:test';
import { currencySeries, summaryCards } from './summary.js';

describe('summaryCards', () => {
  test('four cards in order with the colours of 13 4.1', () => {
    const cards = summaryCards({
      weekly: { count: 2, revenue: 10.5, trend: -3, spark: [1, 2] },
      monthly: { count: 5, revenue: 20, trend: 4, spark: [] },
      total: { count: 9, revenue: 99, trend: 0 },
      refunds: { count: 1, revenue: 5 },
      activeSubscriptions: 7,
    });
    expect(cards.map((c) => [c.key, c.variant, c.kind])).toEqual([
      ['weekly', 'secondary', 'money'],
      ['monthly', 'info', 'money'],
      ['total', 'primary', 'money'],
      ['subscriptions', 'success', 'count'],
    ]);
    expect(cards[0]).toMatchObject({ amount: 10.5, count: 2, trend: -3, spark: [1, 2] });
    expect(cards[3]).toMatchObject({ count: 7, refundsAmount: 5 });
  });
  test('missing or malformed summary yields zeros', () => {
    for (const input of [undefined, null, {}, { weekly: 'x', activeSubscriptions: 'y' }]) {
      const cards = summaryCards(input);
      expect(cards).toHaveLength(4);
      expect(cards.every((c) => c.amount === 0 && c.count === 0 && Array.isArray(c.spark))).toBe(
        true,
      );
    }
  });
  test('subscriptions may be an object', () => {
    const cards = summaryCards({ activeSubscriptions: { count: 3, spark: [1] } });
    expect(cards[3]).toMatchObject({ count: 3, spark: [1] });
  });
  test('refund amount supports both summary shapes', () => {
    expect(summaryCards({ refunds: 12.5 })[3].refundsAmount).toBe(12.5);
    expect(summaryCards({ refunds: { revenue: 7 } })[3].refundsAmount).toBe(7);
  });
});

describe('currencySeries', () => {
  test('rows to labels and values', () => {
    expect(
      currencySeries({
        currencies: [
          { currency: 'USD', amount: 3 },
          { currency: 'EUR', amount: '4' },
        ],
      }),
    ).toEqual({
      labels: ['USD', 'EUR'],
      values: [3, 4],
    });
    expect(currencySeries({})).toEqual({ labels: [], values: [] });
  });
});
