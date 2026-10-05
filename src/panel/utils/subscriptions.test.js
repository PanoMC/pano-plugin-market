import { describe, expect, test } from 'bun:test';
import {
  STATUS_TABS,
  actionsFor,
  activeTab,
  buildCancelBody,
  canRetry,
  cycleText,
  isCancellable,
  listParams,
  methodText,
  priceText,
  renewalBadge,
  validateCancel,
} from './subscriptions.js';
import { NODE } from './permissions.js';

const pay = { admin: false, permissions: [NODE.PAY] };
const ov = { admin: false, permissions: [NODE.OV] };

describe('status tabs', () => {
  test('cancelled tab is the ended csv, in any order', () => {
    expect(STATUS_TABS.find((t) => t.key === 'cancelled').value).toBe(
      'CANCELLED,EXPIRED,COMPLETED',
    );
    expect(activeTab('COMPLETED,CANCELLED,EXPIRED')).toBe('cancelled');
    expect(activeTab('ACTIVE')).toBe('active');
    expect(activeTab(null)).toBe('all');
    expect(activeTab('PAUSED')).toBeNull();
  });
  test('listParams', () => {
    expect(listParams({ status: 'ACTIVE', search: '' }, { search: 'bob' })).toEqual({
      status: 'ACTIVE',
      search: 'bob',
    });
  });
});

describe('actions', () => {
  test('cancel is offered for ACTIVE / PAST_DUE / PAUSED / PENDING only', () => {
    for (const status of ['ACTIVE', 'PAST_DUE', 'PAUSED', 'PENDING'])
      expect(isCancellable({ status })).toBe(true);
    for (const status of ['CANCELLED', 'EXPIRED', 'COMPLETED'])
      expect(isCancellable({ status })).toBe(false);
  });
  test('retry only for a past-due merchant subscription', () => {
    expect(canRetry({ mode: 'MERCHANT', status: 'PAST_DUE' })).toBe(true);
    expect(canRetry({ mode: 'GATEWAY', status: 'PAST_DUE' })).toBe(false);
    expect(canRetry({ mode: 'MANUAL', status: 'PAST_DUE' })).toBe(false);
    expect(canRetry({ mode: 'MERCHANT', status: 'ACTIVE' })).toBe(false);
  });
  test('both actions need PAY', () => {
    const sub = { mode: 'MERCHANT', status: 'PAST_DUE' };
    expect(actionsFor(sub, pay)).toEqual(['cancel', 'retry']);
    expect(actionsFor(sub, ov)).toEqual([]);
    expect(actionsFor({ mode: 'GATEWAY', status: 'ACTIVE' }, pay)).toEqual(['cancel']);
    expect(actionsFor({ mode: 'GATEWAY', status: 'EXPIRED' }, pay)).toEqual([]);
    expect(actionsFor(sub, null)).toEqual([]);
  });
});

describe('cancel form', () => {
  test('period end is the default body, immediately sets atPeriodEnd false', () => {
    expect(buildCancelBody({ timing: 'period-end', reason: '' })).toEqual({ atPeriodEnd: true });
    expect(buildCancelBody({ timing: 'now', reason: ' fraud ' })).toEqual({
      atPeriodEnd: false,
      reason: 'fraud',
    });
  });
  test('reason is limited to 255', () => {
    expect(validateCancel({ timing: 'now', reason: 'x'.repeat(255) }).ok).toBe(true);
    expect(validateCancel({ timing: 'now', reason: 'x'.repeat(256) }).errors.reason).toBe(
      'TOO_LONG',
    );
    expect(validateCancel({ timing: 'later', reason: '' }).ok).toBe(false);
  });
});

describe('cells', () => {
  test('price with and without interval', () => {
    const money = (a, c) => `${a} ${c}`;
    const duration = (u, n) => `${n} ${u}`;
    expect(
      priceText(
        { price: 5, currency: 'USD', intervalUnit: 'MONTH', intervalCount: 1 },
        money,
        duration,
      ),
    ).toBe('5 USD / 1 MONTH');
    expect(priceText({ price: 5, currency: 'USD' }, money, duration)).toBe('5 USD');
  });
  test('cycles and method', () => {
    expect(cycleText({ cycleCount: 3, maxCycles: 12 })).toBe('3 / 12');
    expect(cycleText({ cycleCount: 3 })).toBe('3');
    expect(methodText({ providerId: 'stripe', storedMethodLabel: 'Visa 4242' })).toBe(
      'stripe - Visa 4242',
    );
    expect(methodText({ providerId: 'stripe' })).toBe('stripe');
  });
});

describe('renewalBadge', () => {
  test('maps the four renewal statuses', () => {
    expect(renewalBadge('PAID')).toBe('text-bg-success');
    expect(renewalBadge('PENDING')).toBe('text-bg-warning');
    expect(renewalBadge('FAILED')).toBe('text-bg-danger');
    expect(renewalBadge('SKIPPED')).toBe('text-bg-secondary');
    expect(renewalBadge('WHAT')).toBe('text-bg-secondary');
  });
});
