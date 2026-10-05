import { describe, expect, test } from 'bun:test';
import { changedCategoryFields, formValue, giftDatesReversed, usedCell, validateGiftLimits } from './category-gift.js';

const base = { name: 'A', description: 'd', icon: 'fa-folder', color: '#000', status: 'ACTIVE', tiered: false, upgradeMode: 'DIFFERENCE' };

describe('changedCategoryFields', () => {
  test('nothing changed sends nothing', () => {
    expect(changedCategoryFields(base, { ...base })).toEqual({});
  });
  test('only changed keys are sent', () => {
    expect(changedCategoryFields(base, { ...base, name: 'B', color: '#fff' })).toEqual({ name: 'B', color: '#fff' });
  });
  test('turning tiered on sends tiered and the mode', () => {
    expect(changedCategoryFields(base, { ...base, tiered: true, upgradeMode: 'FULL' })).toEqual({ tiered: true, upgradeMode: 'FULL' });
    expect(changedCategoryFields(base, { ...base, tiered: true })).toEqual({ tiered: true, upgradeMode: 'DIFFERENCE' });
  });
  test('turning tiered off never sends the mode', () => {
    const tiered = { ...base, tiered: true, upgradeMode: 'FULL' };
    expect(changedCategoryFields(tiered, { ...tiered, tiered: false, upgradeMode: 'FULL' })).toEqual({ tiered: false });
  });
  test('changing only the mode of a tiered category', () => {
    const tiered = { ...base, tiered: true, upgradeMode: 'FULL' };
    expect(changedCategoryFields(tiered, { ...tiered, upgradeMode: 'DIFFERENCE' })).toEqual({ upgradeMode: 'DIFFERENCE' });
  });
  test('create (no original) sends every given key', () => {
    expect(changedCategoryFields(null, { name: 'N', tiered: false })).toEqual({ name: 'N', tiered: false });
  });
  test('formValue serialises booleans', () => {
    expect(formValue(true)).toBe('true');
    expect(formValue(false)).toBe('false');
    expect(formValue(null)).toBe('');
  });
});

describe('validateGiftLimits', () => {
  test('defaults: unlimited redeem limit, one per customer', () => {
    const { errors, values } = validateGiftLimits({});
    expect(errors).toEqual({});
    expect(values).toEqual({ name: '', redeemLimit: null, customerRedeemLimit: 1 });
  });
  test('integers >= 1 only', () => {
    expect(validateGiftLimits({ redeemLimit: 0 }).errors.redeemLimit).toBe('INVALID');
    expect(validateGiftLimits({ redeemLimit: '2.5' }).errors.redeemLimit).toBe('INVALID');
    expect(validateGiftLimits({ customerRedeemLimit: 0 }).errors.customerRedeemLimit).toBe('INVALID');
    expect(validateGiftLimits({ redeemLimit: 5, customerRedeemLimit: 2 }).values).toMatchObject({ redeemLimit: 5, customerRedeemLimit: 2 });
  });
  test('name is limited to 255', () => {
    expect(validateGiftLimits({ name: 'x'.repeat(256) }).errors.name).toBe('TOO_LONG');
    expect(validateGiftLimits({ name: 'x'.repeat(255) }).errors.name).toBeUndefined();
  });
  test('dates', () => {
    expect(giftDatesReversed(2, 1)).toBe(true);
    expect(giftDatesReversed(1, 1)).toBe(false);
    expect(giftDatesReversed(null, 1)).toBe(false);
  });
  test('used cell', () => {
    expect(usedCell({ usedCount: 3, redeemLimit: 10 })).toBe('3 / 10');
    expect(usedCell({ usedCount: 3, redeemLimit: null })).toBe('3 / ∞');
    expect(usedCell({})).toBe('0 / ∞');
  });
});

import { siblingMove } from './category-gift.js';

describe('siblingMove', () => {
  const tree = [
    { id: 1, children: [{ id: 11, children: [] }, { id: 12, children: [] }] },
    { id: 2, children: [] },
    { id: 3, children: [] },
  ];
  test('up goes BEFORE the previous sibling, down AFTER the next one', () => {
    expect(siblingMove(tree, 2, 'up')).toEqual({ id: 2, position: 'BEFORE', targetId: 1 });
    expect(siblingMove(tree, 2, 'down')).toEqual({ id: 2, position: 'AFTER', targetId: 3 });
  });
  test('children move among their own siblings only', () => {
    expect(siblingMove(tree, 12, 'up')).toEqual({ id: 12, position: 'BEFORE', targetId: 11 });
    expect(siblingMove(tree, 12, 'down')).toBeNull();
    expect(siblingMove(tree, 11, 'up')).toBeNull();
  });
  test('edges, unknown ids and bad directions give null', () => {
    expect(siblingMove(tree, 1, 'up')).toBeNull();
    expect(siblingMove(tree, 3, 'down')).toBeNull();
    expect(siblingMove(tree, 99, 'up')).toBeNull();
    expect(siblingMove(tree, 2, 'left')).toBeNull();
    expect(siblingMove(null, 2, 'up')).toBeNull();
  });
});
