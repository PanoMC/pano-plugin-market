import { describe, expect, test } from 'bun:test';
import {
  KNOWN_ERRORS,
  PANEL_URL,
  SITE_URL,
  call,
  errorCode,
  errorDetails,
  errorFields,
  errorKey,
  errorParams,
  failureOf,
  idempotencyKeyFor,
  newIdempotency,
  panelUrl,
  siteUrl,
  resetIdempotency,
} from './api.js';
import { uuid } from './uuid.js';

// Every code of 04 section 11 (HTTP table), the six existing codes and the platform codes.
const CODES_04_11 = `
EMPTY_CART INVALID_CART INVALID_COUPON INVALID_CREATOR_CODE INVALID_GIFT_CODE INVALID_RECIPIENT
MINIMUM_ORDER_AMOUNT_NOT_REACHED LEGAL_ACCEPTANCE_REQUIRED BUYER_INFO_REQUIRED SHIPPING_ADDRESS_REQUIRED
SHIPPING_UNAVAILABLE PAYMENT_METHOD_UNAVAILABLE SUBSCRIPTION_MUST_BE_ALONE INSUFFICIENT_CREDITS
INVALID_ORDER_TRANSITION INVALID_REFUND_AMOUNT REFUND_NOT_SUPPORTED CASCADE_DECISION_REQUIRED
STATUS_QUERY_NOT_SUPPORTED INVALID_PROVIDER_SETTINGS INVALID_SETTINGS INVALID_PRODUCT INVALID_WEBHOOK_URL
INVALID_CREDIT_AMOUNT INVALID_PAYOUT_AMOUNT CREATOR_HAS_NO_ACCOUNT PUBLIC_URL_REQUIRED RESERVED_SLUG
INVALID_BLOCK INVALID_SHIPMENT INVALID_SHIPMENT_TRANSITION INVALID_MAIL_KIND MAIL_RECIPIENT_REQUIRED
INVALID_INVOICE_SEQUENCE BUYER_BLOCKED OUT_OF_STOCK PURCHASE_LIMIT_REACHED COOLDOWN_ACTIVE
PRODUCT_REQUIREMENT_NOT_MET PRICE_CHANGED ORDER_NOT_PAYABLE ORDER_NOT_CANCELLABLE ORDER_NOT_SHIPPABLE
SUBSCRIPTION_NOT_CANCELLABLE SUBSCRIPTION_NOT_RESUMABLE SUBSCRIPTION_NOT_RETRYABLE
SUBSCRIPTION_NOT_MANAGEABLE DELIVERY_NOT_RETRYABLE DELIVERY_NOT_CANCELLABLE SHIPMENT_NOT_CANCELLABLE
INVALID_STATE IDEMPOTENCY_CONFLICT PROVIDER_UNAVAILABLE CATEGORY_IN_USE BLOCK_ALREADY_EXISTS
CREDITS_DISABLED MAIL_DISABLED MAIL_NOT_APPLICABLE INVOICE_NOT_ISSUABLE TOO_MANY_REQUESTS
CODE_ATTEMPTS_LOCKED INVOICE_RENDER_FAILED PAYMENT_PROVIDER_ERROR SHIPPING_PROVIDER_ERROR
MAIL_SEND_FAILED STORE_DISABLED STORE_UNAVAILABLE STORE_BUSY
CODE_ALREADY_EXISTS SLUG_ALREADY_EXISTS INVALID_CATEGORY_MOVE INVALID_PASSWORD
PAYMENT_METHOD_NOT_CONFIGURED EXCHANGE_RATE_FETCH_FAILED
BAD_REQUEST NOT_FOUND PAGE_NOT_FOUND NOT_LOGGED_IN NO_PERMISSION INVALID_CSRF_TOKEN
NETWORK_ERROR
`
  .trim()
  .split(/\s+/);

describe('api (13 25.1 tests 12-14)', () => {
  test('12. idempotencyKeyFor: same body same key, changed body new key, reset new key', () => {
    const state = newIdempotency();
    const a = idempotencyKeyFor(state, { amount: 5, note: 'x' });
    expect(idempotencyKeyFor(state, { amount: 5, note: 'x' })).toBe(a);
    const b = idempotencyKeyFor(state, { amount: 6, note: 'x' });
    expect(b).not.toBe(a);
    expect(idempotencyKeyFor(state, { amount: 6, note: 'x' })).toBe(b);
    resetIdempotency(state);
    expect(state).toEqual({ key: null, fingerprint: null });
    const c = idempotencyKeyFor(state, { amount: 6, note: 'x' });
    expect(c).not.toBe(b);
    expect(c).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  test('12b. two states never share a key', () => {
    const keys = new Set();
    for (let i = 0; i < 50; i++) keys.add(idempotencyKeyFor(newIdempotency(), { i: 1 }));
    expect(keys.size).toBe(50);
  });

  test('13. call maps a falsy body to NETWORK_ERROR, {error} to not ok, a body to ok', async () => {
    expect(await call(Promise.resolve(undefined))).toEqual({
      ok: false,
      error: 'NETWORK_ERROR',
      body: {},
      details: {},
      fields: {},
    });
    expect((await call(Promise.resolve(null))).error).toBe('NETWORK_ERROR');
    const failed = await call(
      Promise.resolve({ error: { code: 'NOT_FOUND', details: { extra: 1 } } }),
    );
    expect(failed.ok).toBe(false);
    expect(failed.error).toBe('NOT_FOUND');
    expect(failed.body.extra).toBe(1);
    expect(failed.details).toEqual({ extra: 1 });
    expect(failed.fields).toEqual({});
    expect(await call(Promise.resolve({ id: 7 }))).toEqual({ ok: true, body: { id: 7 } });
    expect((await call(Promise.reject(new Error('boom')))).error).toBe('NETWORK_ERROR');
  });

  test('13b. call maps a non-object body (raw text of a proxy error page) to NETWORK_ERROR', async () => {
    for (const raw of ['<html>502 Bad Gateway</html>', 'x', 42, true]) {
      expect(await call(Promise.resolve(raw))).toEqual({
        ok: false,
        error: 'NETWORK_ERROR',
        body: {},
        details: {},
        fields: {},
      });
    }
    const blob = new Blob(['a,b']);
    expect(await call(Promise.resolve(blob))).toEqual({ ok: true, body: blob });
  });

  test('14. errorKey: unknown code is errors.UNKNOWN, every code of 04 11 is known', () => {
    expect(errorKey('SOMETHING_NEW')).toBe('errors.UNKNOWN');
    expect(errorKey(undefined)).toBe('errors.UNKNOWN');
    expect(errorKey('NO_PERMISSION')).toBe('errors.NO_PERMISSION');
    const missing = CODES_04_11.filter((c) => !KNOWN_ERRORS.has(c));
    expect(missing).toEqual([]);
    for (const c of CODES_04_11) expect(errorKey(c)).toBe(`errors.${c}`);
  });
});

describe('api helpers', () => {
  test('rate limit toast takes a numeric seconds value only', () => {
    expect(errorParams('TOO_MANY_REQUESTS', { retryAfter: 12 })).toEqual({
      values: { seconds: 12 },
    });
    expect(errorParams('CODE_ATTEMPTS_LOCKED', { retryAfter: '<b>x</b>' })).toEqual({
      values: { seconds: 0 },
    });
    expect(errorParams('NOT_FOUND', {})).toEqual({});
  });

  test('the error envelope: code, details and fields (04 section 3)', async () => {
    const body = {
      error: {
        code: 'INVALID_SETTINGS',
        message: 'x',
        details: { fieldErrors: { vatPercent: 'OUT_OF_RANGE' } },
        fields: { email: 'EXISTS' },
      },
    };
    expect(errorCode(body)).toBe('INVALID_SETTINGS');
    expect(errorDetails(body)).toEqual({ fieldErrors: { vatPercent: 'OUT_OF_RANGE' } });
    expect(errorFields(body)).toEqual({ email: 'EXISTS' });
    const failed = await call(Promise.resolve(body));
    expect(failed.error).toBe('INVALID_SETTINGS');
    expect(failed.body.fieldErrors.vatPercent).toBe('OUT_OF_RANGE');
    expect(failed.fields.email).toBe('EXISTS');
    // no details / fields: empty objects, never undefined
    expect(errorDetails({ error: { code: 'NOT_FOUND' } })).toEqual({});
    expect(errorFields({ error: { code: 'NOT_FOUND' } })).toEqual({});
    // a success body, or something that is not a body, has no code
    expect(errorCode({ id: 1 })).toBeNull();
    expect(errorCode(null)).toBeNull();
    expect(errorCode('<html>')).toBeNull();
    // an error without a usable code reads as UNKNOWN (errors.UNKNOWN)
    expect(errorCode({ error: {} })).toBe('UNKNOWN');
    expect(errorCode({ error: 'NOT_FOUND' })).toBe('UNKNOWN');
  });

  test('failureOf: null for a success, the code for an error, NETWORK_ERROR for a non-object', () => {
    expect(failureOf({ items: [] })).toBeNull();
    expect(failureOf({ error: { code: 'PAGE_NOT_FOUND' } })).toBe('PAGE_NOT_FOUND');
    expect(failureOf(undefined)).toBe('NETWORK_ERROR');
    expect(failureOf('<html>502</html>')).toBe('NETWORK_ERROR');
  });

  test('panelUrl / siteUrl: full addresses under /api/v1 for src and href', () => {
    expect(PANEL_URL).toBe('/api/plugins/pano-plugin-market/panel');
    expect(SITE_URL).toBe('/api/plugins/pano-plugin-market');
    expect(panelUrl('/orders/export')).toBe(
      '/api/plugins/pano-plugin-market/panel/orders/export',
    );
    expect(siteUrl('/payment-providers/x/logo')).toBe(
      '/api/plugins/pano-plugin-market/payment-providers/x/logo',
    );
  });

  test('uuid falls back to getRandomValues, then stays a v4 UUID', () => {
    const fake = {
      getRandomValues(bytes) {
        bytes.fill(0xff);
        return bytes;
      },
    };
    expect(uuid(fake)).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff');
    expect(uuid({ randomUUID: () => 'fixed' })).toBe('fixed');
  });
});
