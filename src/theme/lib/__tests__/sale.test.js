import { describe, expect, test } from 'bun:test';
import {
  countdownVisible,
  expiredSaleEnds,
  saleActive,
  saleBadge,
  salePercent,
  strikePrice,
} from '../sale.js';
import { COUNTDOWN_WINDOW_MS } from '../countdown.js';

const on = { modules: { saleBadges: true, saleCountdown: true } };
const off = { modules: { saleBadges: false, saleCountdown: false } };
const NOW = 1_700_000_000_000;

describe('salePercent', () => {
  test('uses sale.percent first', () => {
    expect(salePercent({ price: 8, compareAtPrice: 10, sale: { percent: 25 } })).toBe(25);
  });

  test('derives the percent from compareAtPrice and price', () => {
    expect(salePercent({ price: 7.5, compareAtPrice: 10 })).toBe(25);
    expect(salePercent({ price: 2, compareAtPrice: 3, sale: { percent: null } })).toBe(33);
  });

  test('values below 1 and non-discounts are not shown', () => {
    expect(salePercent({ price: 99.7, compareAtPrice: 100 })).toBe(null);
    expect(salePercent({ price: 10, compareAtPrice: 10 })).toBe(null);
    expect(salePercent({ price: 12, compareAtPrice: 10 })).toBe(null);
    expect(salePercent({ price: 5, compareAtPrice: null })).toBe(null);
    expect(salePercent({ sale: { percent: 0.4 } })).toBe(null);
    expect(salePercent(null)).toBe(null);
  });
});

describe('saleActive', () => {
  test('no sale, no activity', () => {
    expect(saleActive({ sale: null }, NOW)).toBe(false);
    expect(saleActive({}, NOW)).toBe(false);
  });

  test('a sale without an end never expires', () => {
    expect(saleActive({ sale: { percent: 10, endsAt: null } }, NOW)).toBe(true);
  });

  test('ends at the end time; the server render (now 0) trusts the response', () => {
    const product = { sale: { percent: 10, endsAt: NOW } };
    expect(saleActive(product, NOW - 1)).toBe(true);
    expect(saleActive(product, NOW)).toBe(false);
    expect(saleActive(product, NOW + 5)).toBe(false);
    expect(saleActive(product, 0)).toBe(true);
  });
});

describe('saleBadge', () => {
  test('percent badge', () => {
    expect(saleBadge({ sale: { percent: 20 } }, on, NOW)).toEqual({ kind: 'percent', percent: 20 });
  });

  test('amount badge when only amountOff exists', () => {
    expect(saleBadge({ sale: { percent: null, amountOff: 2.5 } }, on, NOW)).toEqual({
      kind: 'amount',
      amount: 2.5,
    });
  });

  test('module off => nothing', () => {
    expect(saleBadge({ sale: { percent: 20 } }, off, NOW)).toBe(null);
    expect(saleBadge({ sale: { percent: 20 } }, {}, NOW)).toBe(null);
  });

  test('a sale that ran out locally hides the badge', () => {
    expect(saleBadge({ sale: { percent: 20, endsAt: NOW - 1 } }, on, NOW)).toBe(null);
  });

  test('a list-price discount without a sale object still gets a percent badge', () => {
    expect(saleBadge({ price: 5, compareAtPrice: 10 }, on, NOW)).toEqual({
      kind: 'percent',
      percent: 50,
    });
  });
});

describe('strikePrice', () => {
  test('only with the module and a lower price', () => {
    const product = { price: 5, compareAtPrice: 10, sale: { percent: 50 } };
    expect(strikePrice(product, on, NOW)).toBe(10);
    expect(strikePrice(product, off, NOW)).toBe(null);
    expect(strikePrice({ price: 10, compareAtPrice: 10 }, on, NOW)).toBe(null);
    expect(strikePrice({ price: 10, compareAtPrice: null }, on, NOW)).toBe(null);
  });

  test('hidden once the sale ran out locally', () => {
    const product = { price: 5, compareAtPrice: 10, sale: { percent: 50, endsAt: NOW - 1000 } };
    expect(strikePrice(product, on, NOW)).toBe(null);
  });
});

describe('countdownVisible', () => {
  const product = (endsAt) => ({ sale: { percent: 10, endsAt } });

  test('within 72 h of the end', () => {
    expect(countdownVisible(product(NOW + 1000), on, NOW)).toBe(true);
    expect(countdownVisible(product(NOW + COUNTDOWN_WINDOW_MS), on, NOW)).toBe(true);
  });

  test('not before the window, not after the end, not without a clock', () => {
    expect(countdownVisible(product(NOW + COUNTDOWN_WINDOW_MS + 1), on, NOW)).toBe(false);
    expect(countdownVisible(product(NOW), on, NOW)).toBe(false);
    expect(countdownVisible(product(NOW + 1000), on, 0)).toBe(false);
    expect(countdownVisible({ sale: { percent: 10 } }, on, NOW)).toBe(false);
    expect(countdownVisible({}, on, NOW)).toBe(false);
  });

  test('module off => never', () => {
    expect(countdownVisible(product(NOW + 1000), off, NOW)).toBe(false);
  });
});

describe('expiredSaleEnds', () => {
  test('returns each ended time once, sorted', () => {
    const products = [
      { sale: { endsAt: NOW - 10 } },
      { sale: { endsAt: NOW - 500 } },
      { sale: { endsAt: NOW - 10 } },
      { sale: { endsAt: NOW + 10 } },
      { sale: null },
      {},
    ];
    expect(expiredSaleEnds(products, NOW)).toEqual([NOW - 500, NOW - 10]);
  });

  test('nothing without a running clock', () => {
    expect(expiredSaleEnds([{ sale: { endsAt: 5 } }], 0)).toEqual([]);
    expect(expiredSaleEnds(undefined, NOW)).toEqual([]);
  });
});
