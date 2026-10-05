import { describe, expect, test } from 'bun:test';
import {
  adjustOutcome,
  amountCheck,
  buildAdjustRequest,
  creditBadgeClass,
  creditsValue,
  noteError,
  normalizeTypes,
  signedAmount,
  toggleType,
  transactionParams,
  validateAdjust,
} from './credits.js';
import { newIdempotency } from './api.js';

const form = (extra = {}) => ({
  mode: 'grant',
  userId: 5,
  amount: '12,5',
  note: 'Event prize',
  ...extra,
});

describe('note and amount validation', () => {
  test('note needs 3 to 255 characters after trimming', () => {
    expect(noteError('')).toBe('REQUIRED');
    expect(noteError('   ')).toBe('REQUIRED');
    expect(noteError('ab')).toBe('TOO_SHORT');
    expect(noteError('  ab  ')).toBe('TOO_SHORT');
    expect(noteError('abc')).toBeNull();
    expect(noteError('a'.repeat(255))).toBeNull();
    expect(noteError('a'.repeat(256))).toBe('TOO_LONG');
  });

  test('amount must be positive with at most two decimals', () => {
    expect(amountCheck('').error).toBe('REQUIRED');
    expect(amountCheck('0').error).toBe('INVALID');
    expect(amountCheck('-3').error).toBe('INVALID');
    expect(amountCheck('1.234').error).toBe('INVALID');
    expect(amountCheck('abc').error).toBe('INVALID');
    expect(amountCheck('1000000.01').error).toBe('TOO_LARGE');
    expect(amountCheck('12,5').value).toBe(12.5);
    expect(amountCheck('1000000').value).toBe(1000000);
  });
});

describe('buildAdjustRequest', () => {
  test('rejects a missing or short note and a bad amount, nothing is sent', () => {
    const idem = newIdempotency();
    expect(buildAdjustRequest(form({ note: 'ab' }), idem).error).toEqual({ note: 'TOO_SHORT' });
    expect(buildAdjustRequest(form({ note: '' }), idem).error).toEqual({ note: 'REQUIRED' });
    expect(buildAdjustRequest(form({ amount: '0' }), idem).error).toEqual({ amount: 'INVALID' });
    expect(buildAdjustRequest(form({ userId: null }), idem).error).toEqual({ user: 'UNKNOWN' });
    expect(idem.key).toBeNull();
  });

  test('builds path, trimmed body and an Idempotency-Key', () => {
    const request = buildAdjustRequest(
      form({ mode: 'revoke', note: '  Chargeback  ' }),
      newIdempotency(),
    );
    expect(request.path).toBe('/credits/accounts/5/revoke');
    expect(request.body).toEqual({ amount: 12.5, note: 'Chargeback' });
    expect(request.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  test('the same body reuses one key, a changed body gets a new one', () => {
    const idem = newIdempotency();
    const a = buildAdjustRequest(form(), idem).headers['Idempotency-Key'];
    const again = buildAdjustRequest(form(), idem).headers['Idempotency-Key'];
    const other = buildAdjustRequest(form({ amount: '13' }), idem).headers['Idempotency-Key'];
    expect(again).toBe(a);
    expect(other).not.toBe(a);
  });

  test('same body for another user or direction never reuses the key', () => {
    const idem = newIdempotency();
    const a = buildAdjustRequest(form(), idem).headers['Idempotency-Key'];
    const user = buildAdjustRequest(form({ userId: 6 }), idem).headers['Idempotency-Key'];
    const revoke = buildAdjustRequest(form({ userId: 6, mode: 'revoke' }), idem).headers[
      'Idempotency-Key'
    ];
    expect(new Set([a, user, revoke]).size).toBe(3);
  });
});

describe('adjustOutcome', () => {
  test('grant success', () => {
    expect(adjustOutcome('grant', 10, { ok: true, body: { balance: 10, shortfall: 0 } })).toEqual({
      kind: 'done',
      reset: true,
      toast: 'toast-granted',
    });
  });

  test('revoke without shortfall', () => {
    const o = adjustOutcome('revoke', 10, { ok: true, body: { balance: 0, shortfall: 0 } });
    expect(o.kind).toBe('done');
    expect(o.toast).toBe('toast-revoked');
  });

  test('over-revoke reports taken and shortfall', () => {
    const o = adjustOutcome('revoke', 10, { ok: true, body: { balance: 0, shortfall: 4.5 } });
    expect(o).toEqual({ kind: 'shortfall', reset: true, taken: 5.5, shortfall: 4.5 });
  });

  test('revoke on an empty balance takes nothing', () => {
    const o = adjustOutcome('revoke', 10, { ok: true, body: { balance: 0, shortfall: 10 } });
    expect(o.kind).toBe('nothing');
  });

  test('grant ignores a shortfall field', () => {
    expect(adjustOutcome('grant', 10, { ok: true, body: { shortfall: 3 } }).kind).toBe('done');
  });

  test('errors: amount mark, conflict resets, network keeps the key', () => {
    expect(adjustOutcome('grant', 1, { ok: false, error: 'INVALID_CREDIT_AMOUNT' })).toEqual({
      kind: 'invalidAmount',
      reset: false,
    });
    expect(adjustOutcome('grant', 1, { ok: false, error: 'IDEMPOTENCY_CONFLICT' }).reset).toBe(
      true,
    );
    expect(adjustOutcome('grant', 1, { ok: false, error: 'NETWORK_ERROR' })).toEqual({
      kind: 'error',
      reset: false,
    });
  });
});

describe('ledger helpers', () => {
  const fmt = (n) => String(n);
  test('entry sign follows the signed amount', () => {
    expect(signedAmount({ type: 'GRANT', amount: 5, balanceAfter: 5 }, fmt)).toBe('+5');
    expect(signedAmount({ type: 'REVOKE', amount: -3, balanceAfter: 2 }, fmt)).toBe('−3');
    expect(creditBadgeClass({ type: 'GRANT', amount: 5, balanceAfter: 5 })).toBe('text-bg-success');
    expect(creditBadgeClass({ type: 'HOLD', amount: -5, balanceAfter: 0 })).toBe('text-bg-danger');
  });

  test('transaction sign follows the type', () => {
    expect(signedAmount({ type: 'REVOKE', amount: 3 }, fmt)).toBe('−3');
    expect(signedAmount({ type: 'TOPUP', amount: 3 }, fmt)).toBe('+3');
    expect(signedAmount({ type: 'CAPTURE', amount: 3 }, fmt)).toBe('3');
  });

  test('value and filters', () => {
    expect(creditsValue(12.5, 0.1)).toBe(1.25);
    expect(normalizeTypes('GRANT,BOGUS,GRANT,REVOKE')).toEqual(['GRANT', 'REVOKE']);
    expect(normalizeTypes(null)).toEqual([]);
    expect(toggleType(['GRANT'], 'REVOKE')).toEqual(['GRANT', 'REVOKE']);
    expect(toggleType(['GRANT', 'REVOKE'], 'GRANT')).toEqual(['REVOKE']);
    expect(
      transactionParams({ types: ['GRANT'], userId: ' 7 ', orderId: 'x', from: 1, to: 2 }),
    ).toEqual({
      section: 'transactions',
      type: 'GRANT',
      userId: '7',
      orderId: null,
      from: 1,
      to: 2,
    });
  });
});

describe('validateAdjust', () => {
  test('reports field errors without touching an idempotency state', () => {
    expect(validateAdjust(form({ note: 'x' })).error).toEqual({ note: 'TOO_SHORT' });
    expect(validateAdjust(form()).amount).toBe(12.5);
  });
});
