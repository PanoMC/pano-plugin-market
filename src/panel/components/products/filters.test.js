import { describe, expect, test } from 'bun:test';
import {
  KIND_FILTERS,
  PRODUCT_PARAMS,
  STATUS_FILTERS,
  hasExtraFilters,
  listParams,
  normalizeFilters,
  pageAfterDelete,
  statusTone,
  stockCell,
  typeBadges,
} from './filters.js';

describe('filters', () => {
  test('the list forwards search, status, kind and categoryId', () => {
    expect(PRODUCT_PARAMS).toEqual(['search', 'status', 'kind', 'categoryId']);
  });
  test('unknown status / kind values and a non-numeric category are dropped', () => {
    expect(normalizeFilters({ status: 'HIDDEN', kind: 'NOPE', categoryId: 'x' })).toEqual({
      search: '',
      status: '',
      kind: '',
      categoryId: '',
    });
    expect(
      normalizeFilters({ status: 'ARCHIVED', kind: 'BUNDLE', categoryId: '12', search: 'a' }),
    ).toEqual({
      search: 'a',
      status: 'ARCHIVED',
      kind: 'BUNDLE',
      categoryId: '12',
    });
  });
  test('every status and kind the spec offers is accepted', () => {
    for (const status of STATUS_FILTERS) expect(normalizeFilters({ status }).status).toBe(status);
    for (const kind of KIND_FILTERS) expect(normalizeFilters({ kind }).kind).toBe(kind);
  });
  test('listParams drops empty values and never carries a page', () => {
    expect(listParams({ search: '', status: 'ACTIVE', kind: '' })).toEqual({ status: 'ACTIVE' });
    expect(listParams({ status: 'ACTIVE', kind: 'BUNDLE' }, { status: null })).toEqual({
      kind: 'BUNDLE',
    });
    expect('page' in listParams({ page: '3', status: 'ACTIVE' })).toBe(false);
  });
  test('hasExtraFilters is true for kind or category only', () => {
    expect(hasExtraFilters({ status: 'ACTIVE', search: 'x' })).toBe(false);
    expect(hasExtraFilters({ kind: 'BUNDLE' })).toBe(true);
    expect(hasExtraFilters({ categoryId: '4' })).toBe(true);
  });
});

describe('row helpers', () => {
  test('type badges: kind, physical, billing mode (not one-time) and variants, in that order', () => {
    expect(typeBadges({ kind: 'STANDARD', billingMode: 'ONE_TIME' }).map((b) => b.key)).toEqual([
      'enums.product-kind.STANDARD',
    ]);
    expect(
      typeBadges({
        kind: 'STANDARD',
        physical: true,
        billingMode: 'SUBSCRIPTION',
        hasVariants: true,
      }).map((b) => b.key),
    ).toEqual([
      'enums.product-kind.STANDARD',
      'pages.products.badge.physical',
      'enums.billing-mode.SUBSCRIPTION',
      'pages.products.badge.variants',
    ]);
    expect(typeBadges({}).map((b) => b.key)).toEqual(['enums.product-kind.STANDARD']);
  });
  test('status tone', () => {
    expect(statusTone('ACTIVE')).toBe('success');
    expect(statusTone('ARCHIVED')).toBe('dark');
    expect(statusTone('INACTIVE')).toBe('secondary');
  });
  test('stock cell: null is unlimited, zero is a count', () => {
    expect(stockCell({ stock: null })).toEqual({ unlimited: true, count: null });
    expect(stockCell({})).toEqual({ unlimited: true, count: null });
    expect(stockCell({ stock: 0 })).toEqual({ unlimited: false, count: 0 });
    expect(stockCell({ stock: 12 })).toEqual({ unlimited: false, count: 12 });
  });
  test('deleting the last row of a later page steps back one page', () => {
    expect(pageAfterDelete(1, 3)).toBe(2);
    expect(pageAfterDelete(1, 1)).toBe(1);
    expect(pageAfterDelete(5, 3)).toBe(3);
  });
});
