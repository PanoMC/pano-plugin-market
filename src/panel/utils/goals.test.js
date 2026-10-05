import { describe, expect, test } from 'bun:test';
import { blankGoal, goalBody, goalToForm, progressPercent, targetValue, validateGoal } from './goals.js';

const valid = (over = {}) => ({ ...blankGoal(), name: 'Server', target: 100, ...over });

describe('validateGoal', () => {
  test('a minimal REVENUE goal is valid', () => {
    expect(validateGoal(valid())).toEqual({});
  });
  test('name is required and limited to 255', () => {
    expect(validateGoal(valid({ name: '  ' })).name).toBe('REQUIRED');
    expect(validateGoal(valid({ name: 'x'.repeat(256) })).name).toBe('TOO_LONG');
    expect(validateGoal(valid({ name: 'x'.repeat(255) })).name).toBeUndefined();
  });
  test('description is limited to 512', () => {
    expect(validateGoal(valid({ description: 'x'.repeat(513) })).description).toBe('TOO_LONG');
    expect(validateGoal(valid({ description: 'x'.repeat(512) })).description).toBeUndefined();
  });
  test('PRODUCT_SALES needs products', () => {
    expect(validateGoal(valid({ metric: 'PRODUCT_SALES' })).productIds).toBe('REQUIRED');
    expect(validateGoal(valid({ metric: 'PRODUCT_SALES', productIds: [3] })).productIds).toBeUndefined();
    expect(validateGoal(valid({ metric: 'ORDERS' })).productIds).toBeUndefined();
  });
  test('target: money > 0 for REVENUE, integer >= 1 otherwise', () => {
    expect(validateGoal(valid({ target: null })).target).toBe('INVALID');
    expect(validateGoal(valid({ target: 0 })).target).toBe('INVALID');
    expect(validateGoal(valid({ target: 12.5 })).target).toBeUndefined();
    expect(validateGoal(valid({ metric: 'ORDERS', target: 12.5 })).target).toBe('INVALID');
    expect(validateGoal(valid({ metric: 'ORDERS', target: 0 })).target).toBe('INVALID');
    expect(validateGoal(valid({ metric: 'ORDERS', target: 1 })).target).toBeUndefined();
    expect(validateGoal(valid({ metric: 'PRODUCT_SALES', productIds: [1], target: '5' })).target).toBeUndefined();
  });
  test('startsAt must be before endsAt', () => {
    expect(validateGoal(valid({ startsAt: 10, endsAt: 10 })).endsAt).toBe('BEFORE_START');
    expect(validateGoal(valid({ startsAt: 11, endsAt: 10 })).endsAt).toBe('BEFORE_START');
    expect(validateGoal(valid({ startsAt: 9, endsAt: 10 })).endsAt).toBeUndefined();
    expect(validateGoal(valid({ startsAt: 9, endsAt: null })).endsAt).toBeUndefined();
  });
});

describe('goalBody', () => {
  test('sends productIds only for PRODUCT_SALES and trims text', () => {
    const revenue = goalBody(valid({ name: ' A ', productIds: [1, 2] }));
    expect(revenue.productIds).toEqual([]);
    expect(revenue.name).toBe('A');
    expect(revenue.status).toBe('ACTIVE');
    const sales = goalBody(valid({ metric: 'PRODUCT_SALES', productIds: ['1', 2], target: '4', active: false }));
    expect(sales.productIds).toEqual([1, 2]);
    expect(sales.target).toBe(4);
    expect(sales.status).toBe('INACTIVE');
  });
  test('goalToForm round-trips a row', () => {
    const row = { name: 'G', metric: 'ORDERS', target: 5, period: 'MONTHLY', status: 'INACTIVE', showOnStore: false, productIds: [] };
    const form = goalToForm(row);
    expect(form.active).toBe(false);
    expect(form.showOnStore).toBe(false);
    expect(goalBody(form)).toMatchObject({ metric: 'ORDERS', target: 5, period: 'MONTHLY', status: 'INACTIVE' });
  });
});

describe('progress', () => {
  test('percent is clamped, junk is 0', () => {
    expect(progressPercent({ percent: 42.4 })).toBe(42);
    expect(progressPercent({ percent: 250 })).toBe(100);
    expect(progressPercent({ percent: -3 })).toBe(0);
    expect(progressPercent({ percent: 'x' })).toBe(0);
    expect(progressPercent({})).toBe(0);
  });
  test('targetValue is NaN for empty', () => {
    expect(Number.isNaN(targetValue({ metric: 'ORDERS', target: '' }))).toBe(true);
  });
});
