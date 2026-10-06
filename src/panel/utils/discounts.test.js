import { describe, expect, test } from 'bun:test';
import { newIdempotency } from './api.js';
import { newAction } from './actions.js';
import {
  SERVER_OWNED_COUNTERS,
  buildCouponBody,
  buildCreatorBody,
  buildDiscountBody,
  buildPayoutBody,
  buildPayoutRequest,
  canCancelPayout,
  canPayOut,
  checkLimit,
  checkPayoutAmount,
  checkValue,
  datesToForm,
  filterCreators,
  generateCode,
  payoutCredits,
  payoutFailure,
  redemptionBadge,
  redemptionsPath,
  stripCounters,
  unitOf,
} from './discounts.js';

const discount = (over = {}) => ({
  name: ' Summer ',
  value: '15',
  unit: 'PERCENT',
  minPaymentAmount: '',
  scope: 'ALL',
  productIds: [],
  categoryIds: [],
  active: true,
  unlimitedDates: true,
  startDate: '',
  expiryDate: '',
  unlimitedUsage: true,
  usageLimit: '',
  showBadge: false,
  ...over,
});

describe('discount form', () => {
  test('builds the enum unit, never the old currency sentinel, and the sale badge flag', () => {
    const { body } = buildDiscountBody(discount({ unit: 'FIXED', value: '2,5', showBadge: true }));
    expect(body).toEqual({
      name: 'Summer',
      value: 2.5,
      unit: 'FIXED',
      scope: 'ALL',
      status: 'ACTIVE',
      showBadge: true,
    });
    expect(unitOf('₺')).toBe('PERCENT');
  });

  test('percent is 0-100, fixed is >= 0 with two decimals', () => {
    expect(checkValue('100', 'PERCENT').value).toBe(100);
    expect(checkValue('100.01', 'PERCENT').error).toBe('TOO_LARGE');
    expect(checkValue('-1', 'PERCENT').error).toBe('INVALID');
    expect(checkValue('1.234', 'FIXED').error).toBe('INVALID');
    expect(checkValue('', 'FIXED').error).toBe('REQUIRED');
    expect(checkValue('500', 'FIXED').value).toBe(500);
  });

  test('limits are integers >= 0 or unlimited', () => {
    expect(checkLimit('', true).value).toBeNull();
    expect(checkLimit('0', false).value).toBe(0);
    expect(checkLimit('-3', false).error).toBe('INVALID');
    expect(checkLimit('1.5', false).error).toBe('INVALID');
    expect(checkLimit('', false).error).toBe('REQUIRED');
  });

  test('startDate after expiryDate is rejected on the expiry field', () => {
    const { errors } = buildDiscountBody(
      discount({ unlimitedDates: false, startDate: '2026-12-01', expiryDate: '2026-11-01' }),
    );
    expect(errors.expiryDate).toBe('DATES_REVERSED');
    const ok = buildDiscountBody(
      discount({ unlimitedDates: false, startDate: '2026-11-01', expiryDate: '2026-11-01' }),
    );
    expect(ok.body.startDate).toBeLessThan(ok.body.expiryDate);
  });

  test('required fields and scope lists are marked', () => {
    const { errors } = buildDiscountBody(
      discount({ name: '  ', value: '', scope: 'PRODUCTS', unlimitedUsage: false }),
    );
    expect(errors).toEqual({
      name: 'REQUIRED',
      value: 'REQUIRED',
      usageLimit: 'REQUIRED',
      productIds: 'REQUIRED',
    });
    expect(buildDiscountBody(discount({ name: 'x'.repeat(256) })).errors.name).toBe('TOO_LONG');
  });

  test('counters are never sent', () => {
    const { body } = buildDiscountBody(
      discount({ usedCount: 9, earnings: 1, paidOut: 2, unlimitedUsage: false, usageLimit: '5' }),
    );
    for (const key of SERVER_OWNED_COUNTERS) expect(key in body).toBe(false);
    expect(body.usageLimit).toBe(5);
    expect(stripCounters({ a: 1, usedCount: 2, earnings: 3, paidOut: 4 })).toEqual({ a: 1 });
  });
});

describe('coupon and creator forms', () => {
  const coupon = (over = {}) => ({
    name: '',
    code: ' SAVE10 ',
    discount: '10',
    unit: 'PERCENT',
    minPaymentAmount: '',
    scope: 'ALL',
    productIds: [],
    categoryIds: [],
    active: false,
    unlimitedDates: true,
    startDate: '',
    expiryDate: '',
    unlimitedRedeem: true,
    redeemLimit: '',
    unlimitedCustomerRedeem: false,
    customerRedeemLimit: '1',
    ...over,
  });

  test('coupon body: category ids next to products when scope is SELECTED', () => {
    const { body } = buildCouponBody(
      coupon({ scope: 'SELECTED', categoryIds: [3], productIds: [] }),
    );
    expect(body.categoryIds).toEqual([3]);
    expect(body.productIds).toEqual([]);
    expect(body.status).toBe('INACTIVE');
    expect(body.customerRedeemLimit).toBe(1);
    expect('usedCount' in body).toBe(false);
    expect(buildCouponBody(coupon({ scope: 'SELECTED' })).errors.productIds).toBe('REQUIRED');
    expect(buildCouponBody(coupon({ scope: 'ALL' })).body.categoryIds).toBeUndefined();
  });

  const creator = (over = {}) => ({
    creator: 'Alex',
    code: 'ALEX',
    discount: '5',
    unit: 'FIXED',
    commission: '12.5',
    active: true,
    unlimitedDates: true,
    startDate: '',
    expiryDate: '',
    unlimitedRedeem: true,
    redeemLimit: '',
    ...over,
  });

  test('creator body never carries creatorUserId or the counters; commission 0-100', () => {
    const { body } = buildCreatorBody(creator({ creatorUserId: 7, earnings: 5, paidOut: 1 }));
    expect(body).toEqual({
      creator: 'Alex',
      code: 'ALEX',
      discount: 5,
      unit: 'FIXED',
      commissionPercent: 12.5,
      status: 'ACTIVE',
    });
    expect(buildCreatorBody(creator({ commission: '101' })).errors.commission).toBe('TOO_LARGE');
    expect(buildCreatorBody(creator({ commission: '' })).body.commissionPercent).toBe(0);
    expect(buildCreatorBody(creator({ creator: '', code: '' })).errors).toEqual({
      creator: 'REQUIRED',
      code: 'REQUIRED',
    });
  });

  test('rows load into the form', () => {
    expect(datesToForm({}).unlimitedDates).toBe(true);
    expect(datesToForm({ expiryDate: new Date(2026, 5, 1).getTime() })).toEqual({
      unlimitedDates: false,
      startDate: '',
      expiryDate: '2026-06-01',
    });
  });
});

describe('payout', () => {
  test('amount above available is rejected on the amount field', () => {
    expect(checkPayoutAmount('10.01', 10).error).toBe('EXCEEDS_AVAILABLE');
    expect(checkPayoutAmount('10', 10).value).toBe(10);
    expect(checkPayoutAmount('0', 10).error).toBe('INVALID');
    expect(checkPayoutAmount('5', -2).error).toBe('NOTHING_AVAILABLE');
    expect(checkPayoutAmount('', 10).error).toBe('REQUIRED');
    expect(checkPayoutAmount('1.234', 10).error).toBe('INVALID');
    const { errors } = buildPayoutBody(
      { amount: '11', method: 'MANUAL', note: 'wire' },
      { available: 10 },
    );
    expect(errors).toEqual({ amount: 'EXCEEDS_AVAILABLE' });
  });

  test('a MANUAL payout needs a note (the endpoint answers 400 note: REQUIRED), CREDIT and ACTION do not', () => {
    const manual = { amount: '3', method: 'MANUAL' };
    expect(buildPayoutBody({ ...manual, note: '' }, { available: 10 }).errors).toEqual({
      note: 'REQUIRED',
    });
    expect(buildPayoutBody({ ...manual, note: '   ' }, { available: 10 }).errors.note).toBe(
      'REQUIRED',
    );
    expect(buildPayoutBody({ ...manual, note: 'bank wire' }, { available: 10 }).body).toEqual({
      amount: 3,
      method: 'MANUAL',
      note: 'bank wire',
    });
    expect(
      buildPayoutBody({ amount: '3', method: 'CREDIT', note: '' }, { available: 10 }).body,
    ).toEqual({
      amount: 3,
      method: 'CREDIT',
    });
  });

  test('credits preview is half-up and needs a credit value', () => {
    expect(payoutCredits(10, 4)).toBe(2.5);
    expect(payoutCredits(1, 3)).toBe(0.33);
    expect(payoutCredits(2, 3)).toBe(0.67);
    expect(payoutCredits(1, 0)).toBeNull();
  });

  test('ACTION needs at least one valid action; GRANT phase, no phase select', () => {
    const base = { amount: '5', method: 'ACTION', note: '' };
    expect(buildPayoutBody({ ...base, actions: [] }, { available: 10 }).errors.actions).toBe(
      'REQUIRED',
    );
    const bad = newAction('COMMAND');
    expect(
      buildPayoutBody({ ...base, actions: [bad] }, { available: 10 }).errors['actions.0.value.0'],
    ).toBe('INVALID_VALUE');
    const good = { ...newAction('COMMAND'), value: ['/give {player} diamond'], targetServers: [1] };
    const { body } = buildPayoutBody({ ...base, actions: [good] }, { available: 10 });
    expect(body.method).toBe('ACTION');
    expect(body.actions[0].value).toEqual(['give {player} diamond']);
  });

  test('CREDIT / MANUAL bodies carry no actions; note trimmed and optional', () => {
    const { body } = buildPayoutBody(
      { amount: '3', method: 'CREDIT', note: '  thanks ', actions: [newAction('COMMAND')] },
      { available: 10 },
    );
    expect(body).toEqual({ amount: 3, method: 'CREDIT', note: 'thanks' });
    expect(
      buildPayoutBody({ amount: '3', method: 'MANUAL', note: 'x'.repeat(256) }, { available: 10 })
        .errors.note,
    ).toBe('TOO_LONG');
  });

  test('request: same body keeps the idempotency key, another body gets a new one', () => {
    const state = newIdempotency();
    const form = { amount: '4', method: 'MANUAL', note: 'wire' };
    const a = buildPayoutRequest(9, form, { available: 10 }, state);
    const b = buildPayoutRequest(9, form, { available: 10 }, state);
    expect(a.path).toBe('/creator-codes/9/payouts');
    expect(a.headers['Idempotency-Key']).toBe(b.headers['Idempotency-Key']);
    const c = buildPayoutRequest(9, { ...form, amount: '5' }, { available: 10 }, state);
    expect(c.headers['Idempotency-Key']).not.toBe(a.headers['Idempotency-Key']);
    expect(
      buildPayoutRequest(9, { ...form, amount: '50' }, { available: 10 }, state).errors,
    ).toEqual({
      amount: 'EXCEEDS_AVAILABLE',
    });
  });

  test('server failures mark the amount / the CREDIT method', () => {
    expect(
      payoutFailure({ ok: false, error: 'INVALID_PAYOUT_AMOUNT', body: { available: 2.5 } }),
    ).toEqual({ field: 'amount', reset: false, available: 2.5 });
    expect(payoutFailure({ ok: false, error: 'CREATOR_HAS_NO_ACCOUNT', body: {} }).field).toBe(
      'method',
    );
    expect(payoutFailure({ ok: false, error: 'IDEMPOTENCY_CONFLICT', body: {} }).reset).toBe(true);
    expect(payoutFailure({ ok: false, error: 'NETWORK_ERROR', body: {} }).reset).toBe(false);
  });
});

describe('report helpers', () => {
  const rows = [
    { id: 1, creator: 'Alex', code: 'ALEX10', available: 5 },
    { id: 2, creator: 'Steve', code: 'MINE', available: 0 },
  ];
  test('client-side search over creator and code', () => {
    expect(filterCreators(rows, 'ale').map((r) => r.id)).toEqual([1]);
    expect(filterCreators(rows, 'mine').map((r) => r.id)).toEqual([2]);
    expect(filterCreators(rows, '  ')).toHaveLength(2);
  });
  test('pay out needs PAY and available > 0; cancel needs PAY and PENDING', () => {
    expect(canPayOut(rows[0], true)).toBe(true);
    expect(canPayOut(rows[0], false)).toBe(false);
    expect(canPayOut(rows[1], true)).toBe(false);
    expect(canPayOut({ available: -3 }, true)).toBe(false);
    expect(canCancelPayout({ state: 'PENDING' }, true)).toBe(true);
    expect(canCancelPayout({ state: 'PAID' }, true)).toBe(false);
    expect(canCancelPayout({ state: 'PENDING' }, false)).toBe(false);
  });
  test('redemption badges', () => {
    expect(redemptionBadge('APPLIED')).toBe('text-bg-success');
    expect(redemptionBadge('HELD')).toBe('text-bg-warning');
    expect(redemptionBadge('RELEASED')).toBe('text-bg-secondary');
  });
  test('code generator is 8 chars of the alphabet', () => {
    expect(generateCode(() => 0)).toBe('AAAAAAAA');
    expect(generateCode(() => 0.999999)).toBe('99999999');
  });
  test('redemption paths', () => {
    expect(redemptionsPath('coupons', 4)).toBe('/coupons/4/redemptions');
    expect(redemptionsPath('gifts', 4, 3)).toBe('/gifts/4/redemptions?page=3');
    expect(redemptionsPath('nope', 4)).toBeNull();
  });
});
