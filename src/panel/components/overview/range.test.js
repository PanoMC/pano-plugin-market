import { describe, expect, test } from 'bun:test';
import { parseRange, rangeQuery, rangeToParams } from './range.js';

const NOW = new Date(2026, 9, 15, 13, 30).getTime(); // 15 Oct 2026, local

describe('rangeToParams', () => {
  test('7d / 30d / 90d are N calendar days including today', () => {
    const r = rangeToParams('7d', NOW);
    expect(new Date(r.from)).toEqual(new Date(2026, 9, 9, 0, 0, 0, 0));
    expect(new Date(r.to)).toEqual(new Date(2026, 9, 15, 23, 59, 59, 999));
    expect(new Date(rangeToParams('30d', NOW).from)).toEqual(new Date(2026, 8, 16));
    expect(new Date(rangeToParams('90d', NOW).from)).toEqual(new Date(2026, 6, 18));
  });

  test('month starts on the first of the current month', () => {
    expect(new Date(rangeToParams('month', NOW).from)).toEqual(new Date(2026, 9, 1));
  });
});

describe('parseRange', () => {
  const sp = (q) => new URL('http://x/market' + q).searchParams;
  test('defaults to 30d', () => {
    expect(parseRange(sp(''), NOW)).toEqual({ range: '30d', ...rangeToParams('30d', NOW) });
    expect(parseRange(sp('?range=bogus'), NOW).range).toBe('30d');
  });
  test('known ranges', () => {
    expect(parseRange(sp('?range=7d'), NOW).range).toBe('7d');
    expect(parseRange(sp('?range=month'), NOW).range).toBe('month');
  });
  test('from / to is the custom range', () => {
    expect(parseRange(sp('?from=1000&to=2000'), NOW)).toEqual({
      range: 'custom',
      from: 1000,
      to: 2000,
    });
  });
  test('half, reversed or non numeric custom ranges are ignored', () => {
    expect(parseRange(sp('?from=1000'), NOW).range).toBe('30d');
    expect(parseRange(sp('?from=2000&to=1000'), NOW).range).toBe('30d');
    expect(parseRange(sp('?from=a&to=b'), NOW).range).toBe('30d');
    expect(parseRange(sp('?from=-5&to=10'), NOW).range).toBe('30d');
  });
});

describe('rangeQuery', () => {
  test('default keeps the url clean', () => {
    expect(rangeQuery('30d')).toEqual({ range: null, from: null, to: null });
    expect(rangeQuery('90d')).toEqual({ range: '90d', from: null, to: null });
    expect(rangeQuery('custom', 1, 2)).toEqual({ range: null, from: 1, to: 2 });
  });
});

import { overviewQuery } from './range.js';

describe('overviewQuery', () => {
  test('range and view', () => {
    expect(overviewQuery({ range: '30d', view: 'table' })).toEqual({
      range: null,
      from: null,
      to: null,
      view: null,
    });
    expect(overviewQuery({ range: '7d', view: 'chart' })).toEqual({
      range: '7d',
      from: null,
      to: null,
      view: 'chart',
    });
    expect(overviewQuery({ range: 'custom', from: 5, to: 6 })).toEqual({
      range: null,
      from: 5,
      to: 6,
      view: null,
    });
  });
});
