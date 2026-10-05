import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { STATUS_KINDS, badgeClass, isKnownStatus, statusValues } from './status.js';

// Enum values of 00 section 7 (state machines) and 01 (data model) for the sixteen kinds.
const SPEC = {
  order: 'PENDING REVIEW COMPLETED PARTIALLY_REFUNDED REFUNDED CHARGEBACK FAILED CANCELLED EXPIRED',
  payment: 'CREATED PENDING PROCESSING SUCCEEDED FAILED CANCELLED EXPIRED REVIEW',
  refund: 'REQUESTED PENDING SUCCEEDED FAILED CANCELLED',
  delivery:
    'PENDING SENDING SCHEDULED WAITING_SERVER QUEUED WAITING_PLAYER CONFIRMED SENT FAILED CANCELLED',
  fulfillment: 'NONE PENDING PARTIAL FULFILLED FAILED REVOKED',
  shipping: 'NOT_REQUIRED PENDING PARTIAL SHIPPED DELIVERED RETURNED',
  shipment:
    'CREATED LABEL_READY IN_TRANSIT OUT_FOR_DELIVERY DELIVERED EXCEPTION RETURNING RETURNED CANCELLED LOST',
  subscription: 'PENDING ACTIVE PAST_DUE PAUSED CANCELLED EXPIRED COMPLETED',
  dispute: 'NONE OPEN INQUIRY WON LOST CLOSED',
  webhook: 'PENDING SENDING SUCCEEDED FAILED DEAD',
  earning: 'PENDING AVAILABLE PAID REVERSED',
  payout: 'PENDING PAID FAILED CANCELLED',
  provider: 'ACTIVE DISABLED NOT_CONFIGURED INCOMPATIBLE UNAVAILABLE',
  entitlement: 'ACTIVE EXPIRED REVOKED UPGRADED',
  event: 'RECEIVED PROCESSED DUPLICATE REJECTED FAILED DEFERRED SUPERSEDED',
  mail: 'PENDING SENDING SENT FAILED SKIPPED',
};
const valid = new Set([
  'text-bg-success',
  'text-bg-warning',
  'text-bg-danger',
  'text-bg-info',
  'text-bg-secondary',
  'text-bg-primary',
]);

const localeDir = path.resolve(import.meta.dir, '../../locales/panel');
const locale = (lang) => JSON.parse(fs.readFileSync(path.join(localeDir, `${lang}.json`), 'utf8'));

describe('status (13 25.1 test 15)', () => {
  test('the table covers exactly the sixteen kinds', () => {
    expect([...STATUS_KINDS].sort()).toEqual(Object.keys(SPEC).sort());
  });

  test('every spec enum value maps to a Bootstrap badge class', () => {
    for (const [kind, text] of Object.entries(SPEC))
      for (const value of text.split(' ')) {
        expect(isKnownStatus(kind, value)).toBe(true);
        const cls = badgeClass(kind, value);
        expect(cls).toBeDefined();
        expect(valid.has(cls)).toBe(true);
      }
  });

  test('the table holds no value the spec does not list', () => {
    for (const [kind, text] of Object.entries(SPEC))
      expect(statusValues(kind).sort()).toEqual(text.split(' ').sort());
  });

  test('unknown value or kind is text-bg-secondary and not known', () => {
    expect(badgeClass('order', 'WHATEVER')).toBe('text-bg-secondary');
    expect(badgeClass('nope', 'COMPLETED')).toBe('text-bg-secondary');
    expect(isKnownStatus('order', 'WHATEVER')).toBe(false);
  });

  test('spot checks of the 3.5 table', () => {
    expect(badgeClass('order', 'COMPLETED')).toBe('text-bg-success');
    expect(badgeClass('order', 'REFUNDED')).toBe('text-bg-info');
    expect(badgeClass('order', 'CHARGEBACK')).toBe('text-bg-danger');
    expect(badgeClass('delivery', 'SENT')).toBe('text-bg-info');
    expect(badgeClass('delivery', 'WAITING_SERVER')).toBe('text-bg-warning');
    expect(badgeClass('webhook', 'FAILED')).toBe('text-bg-warning');
    expect(badgeClass('webhook', 'DEAD')).toBe('text-bg-danger');
    expect(badgeClass('earning', 'AVAILABLE')).toBe('text-bg-info');
    expect(badgeClass('shipment', 'LOST')).toBe('text-bg-danger');
    expect(badgeClass('provider', 'DISABLED')).toBe('text-bg-secondary');
  });

  test('every value has enums.<kind>.<VALUE> in tr, en-US and ru', () => {
    for (const lang of ['tr', 'en-US', 'ru']) {
      const enums = locale(lang).enums;
      for (const [kind, text] of Object.entries(SPEC))
        for (const value of text.split(' ')) expect(typeof enums?.[kind]?.[value]).toBe('string');
    }
  });
});
