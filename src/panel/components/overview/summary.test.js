import { describe, expect, test } from 'bun:test';
import { currencySeries, summaryCards } from './summary.js';

describe('summaryCards', () => {
  test('five cards in order with the colours of 13 4.1', () => {
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
      ['refunds', 'warning', 'money'],
      ['subscriptions', 'success', 'count'],
    ]);
    expect(cards[0]).toMatchObject({ amount: 10.5, count: 2, trend: -3, spark: [1, 2] });
    expect(cards[4].count).toBe(7);
  });
  test('missing or malformed summary yields zeros', () => {
    for (const input of [undefined, null, {}, { weekly: 'x', activeSubscriptions: 'y' }]) {
      const cards = summaryCards(input);
      expect(cards).toHaveLength(5);
      expect(cards.every((c) => c.amount === 0 && c.count === 0 && Array.isArray(c.spark))).toBe(
        true,
      );
    }
  });
  test('refunds may be a bare amount and subscriptions an object', () => {
    const cards = summaryCards({ refunds: 12.5, activeSubscriptions: { count: 3, spark: [1] } });
    expect(cards[3].amount).toBe(12.5);
    expect(cards[4]).toMatchObject({ count: 3, spark: [1] });
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
