import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import {
  CHECKOUT_ACTION_CODES,
  CREDIT_AMOUNT_REASONS,
  ERROR_KEYS,
  HTTP_CODES,
  MESSAGE_CODES,
  checkoutAction,
  creditAmountKey,
  isTerminal,
  messageKey,
  reasonKey,
} from '../errorMap.js';

const locale = (lang) =>
  JSON.parse(
    fs.readFileSync(new URL(`../../../locales/theme/${lang}.json`, import.meta.url), 'utf8'),
  ).theme.errors;
const LOCALES = Object.fromEntries(['en-US', 'tr', 'ru'].map((lang) => [lang, locale(lang)]));

// Every code of the tables of 04 §11 (HTTP codes first, then the platform codes market reuses).
const SECTION_11 = [
  'EMPTY_CART',
  'INVALID_CART',
  'INVALID_COUPON',
  'INVALID_CREATOR_CODE',
  'INVALID_GIFT_CODE',
  'INVALID_RECIPIENT',
  'MINIMUM_ORDER_AMOUNT_NOT_REACHED',
  'LEGAL_ACCEPTANCE_REQUIRED',
  'BUYER_INFO_REQUIRED',
  'SHIPPING_ADDRESS_REQUIRED',
  'SHIPPING_UNAVAILABLE',
  'PAYMENT_METHOD_UNAVAILABLE',
  'SUBSCRIPTION_MUST_BE_ALONE',
  'INSUFFICIENT_CREDITS',
  'INVALID_ORDER_TRANSITION',
  'INVALID_REFUND_AMOUNT',
  'REFUND_NOT_SUPPORTED',
  'CASCADE_DECISION_REQUIRED',
  'STATUS_QUERY_NOT_SUPPORTED',
  'INVALID_PROVIDER_SETTINGS',
  'INVALID_SETTINGS',
  'INVALID_PRODUCT',
  'INVALID_WEBHOOK_URL',
  'INVALID_CREDIT_AMOUNT',
  'INVALID_PAYOUT_AMOUNT',
  'CREATOR_HAS_NO_ACCOUNT',
  'PUBLIC_URL_REQUIRED',
  'RESERVED_SLUG',
  'INVALID_BLOCK',
  'INVALID_SHIPMENT',
  'INVALID_SHIPMENT_TRANSITION',
  'INVALID_MAIL_KIND',
  'MAIL_RECIPIENT_REQUIRED',
  'INVALID_INVOICE_SEQUENCE',
  'BUYER_BLOCKED',
  'OUT_OF_STOCK',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'PRODUCT_REQUIREMENT_NOT_MET',
  'PRICE_CHANGED',
  'ORDER_NOT_PAYABLE',
  'ORDER_NOT_CANCELLABLE',
  'ORDER_NOT_SHIPPABLE',
  'SUBSCRIPTION_NOT_CANCELLABLE',
  'SUBSCRIPTION_NOT_RESUMABLE',
  'SUBSCRIPTION_NOT_RETRYABLE',
  'SUBSCRIPTION_NOT_MANAGEABLE',
  'DELIVERY_NOT_RETRYABLE',
  'DELIVERY_NOT_CANCELLABLE',
  'SHIPMENT_NOT_CANCELLABLE',
  'INVALID_STATE',
  'IDEMPOTENCY_CONFLICT',
  'PROVIDER_UNAVAILABLE',
  'CATEGORY_IN_USE',
  'BLOCK_ALREADY_EXISTS',
  'CREDITS_DISABLED',
  'MAIL_DISABLED',
  'MAIL_NOT_APPLICABLE',
  'INVOICE_NOT_ISSUABLE',
  'TOO_MANY_REQUESTS',
  'CODE_ATTEMPTS_LOCKED',
  'INVOICE_RENDER_FAILED',
  'PAYMENT_PROVIDER_ERROR',
  'SHIPPING_PROVIDER_ERROR',
  'MAIL_SEND_FAILED',
  'STORE_DISABLED',
  'STORE_UNAVAILABLE',
  'STORE_BUSY',
  'CODE_ALREADY_EXISTS',
  'SLUG_ALREADY_EXISTS',
  'INVALID_CATEGORY_MOVE',
  'INVALID_PASSWORD',
  'PAYMENT_METHOD_NOT_CONFIGURED',
  'EXCHANGE_RATE_FETCH_FAILED',
  'BAD_REQUEST',
  'NOT_FOUND',
  'PAGE_NOT_FOUND',
  'NOT_LOGGED_IN',
  'NO_PERMISSION',
  'INVALID_CSRF_TOKEN',
];

// Codes only a panel call can answer: the storefront never shows them (they read as the generic text).
const PANEL_ONLY = [
  'INVALID_ORDER_TRANSITION',
  'INVALID_REFUND_AMOUNT',
  'REFUND_NOT_SUPPORTED',
  'CASCADE_DECISION_REQUIRED',
  'STATUS_QUERY_NOT_SUPPORTED',
  'INVALID_PROVIDER_SETTINGS',
  'INVALID_SETTINGS',
  'INVALID_PRODUCT',
  'INVALID_WEBHOOK_URL',
  'INVALID_PAYOUT_AMOUNT',
  'CREATOR_HAS_NO_ACCOUNT',
  'PUBLIC_URL_REQUIRED',
  'RESERVED_SLUG',
  'INVALID_BLOCK',
  'INVALID_SHIPMENT',
  'INVALID_SHIPMENT_TRANSITION',
  'INVALID_MAIL_KIND',
  'MAIL_RECIPIENT_REQUIRED',
  'INVALID_INVOICE_SEQUENCE',
  'ORDER_NOT_SHIPPABLE',
  'DELIVERY_NOT_RETRYABLE',
  'DELIVERY_NOT_CANCELLABLE',
  'SHIPMENT_NOT_CANCELLABLE',
  'INVALID_STATE',
  'PROVIDER_UNAVAILABLE',
  'CATEGORY_IN_USE',
  'BLOCK_ALREADY_EXISTS',
  'MAIL_DISABLED',
  'MAIL_NOT_APPLICABLE',
  'INVOICE_NOT_ISSUABLE',
  'INVOICE_RENDER_FAILED',
  'SHIPPING_PROVIDER_ERROR',
  'MAIL_SEND_FAILED',
  'CODE_ALREADY_EXISTS',
  'SLUG_ALREADY_EXISTS',
  'INVALID_CATEGORY_MOVE',
  'INVALID_PASSWORD',
  'PAYMENT_METHOD_NOT_CONFIGURED',
  'EXCHANGE_RATE_FETCH_FAILED',
];

// The message codes of the last paragraph of 04 §11.
const SECTION_11_MESSAGES = [
  'PRODUCT_UNAVAILABLE',
  'VARIANT_REQUIRED',
  'VARIANT_UNAVAILABLE',
  'OUT_OF_STOCK',
  'QUANTITY_REDUCED',
  'MAX_QUANTITY',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'REQUIREMENT_NOT_MET',
  'PERMISSION_REQUIRED',
  'ALREADY_OWNED',
  'FIELD_REQUIRED',
  'FIELD_INVALID',
  'SERVER_REQUIRED',
  'SERVER_UNAVAILABLE',
  'GIFT_NOT_ALLOWED',
  'RECIPIENT_UNKNOWN',
  'LOGIN_REQUIRED',
  'NOT_IN_CURRENCY',
  'COUPON_NOT_APPLICABLE',
  'CODE_EXPIRED',
  'CODE_LIMIT_REACHED',
  'CODE_NOT_STARTED',
  'CODE_MIN_AMOUNT',
  'CURRENCY_NOT_SUPPORTED',
  'AMOUNT_BELOW_MINIMUM',
  'AMOUNT_ABOVE_MAXIMUM',
  'GUESTS_NOT_SUPPORTED',
  'PHYSICAL_NOT_SUPPORTED',
  'RECURRING_NOT_SUPPORTED',
  'EXTERNAL_PRICING',
  'MIXED_CREDIT_NOT_SUPPORTED',
  'PROVIDER_INELIGIBLE',
  'BUYER_BLOCKED',
  'TEST_MODE',
  'CODE_NOT_FOUND',
  'CODE_NOT_COMBINABLE',
  'CODE_ATTEMPTS_LOCKED',
  'CREDITS_ONLY',
  'NOT_PAYABLE_WITH_CREDITS',
  'CREDIT_PACK_SEPARATE_ORDER',
  'CREDITS_REDUCED',
  'INSUFFICIENT_CREDITS',
  'INVALID_CREDIT_AMOUNT',
  'MINIMUM_ORDER_AMOUNT_NOT_REACHED',
  'SUBSCRIPTION_MUST_BE_ALONE',
  'SHIPPING_ADDRESS_REQUIRED',
  'SHIPPING_ADDRESS_INVALID',
  'SHIPPING_UNAVAILABLE',
  'SHIPPING_METHOD_REQUIRED',
];

describe('message keys', () => {
  test('the test list of 04 §11 and the module agree on what reaches the storefront', () => {
    const storefront = SECTION_11.filter((code) => !PANEL_ONLY.includes(code));

    for (const code of storefront) expect(ERROR_KEYS).toContain(code);
    for (const code of HTTP_CODES) expect(SECTION_11).toContain(code);
    for (const code of MESSAGE_CODES.filter((c) => !SECTION_11_MESSAGES.includes(c)))
      expect(['CREDITS_REQUIRED', 'CREDITS_DISABLED']).toContain(code);
  });

  test('every storefront code of 04 §11 has a key in en-US, tr and ru', () => {
    const storefront = [
      ...SECTION_11.filter((code) => !PANEL_ONLY.includes(code)),
      ...SECTION_11_MESSAGES,
      'NETWORK',
      'GENERIC',
      'SESSION_EXPIRED',
      'INVALID_GIFT_CODE',
    ];

    for (const [lang, texts] of Object.entries(LOCALES))
      for (const code of storefront) {
        expect(typeof texts[code], `${lang}: ${code}`).toBe('string');
        expect(texts[code].trim().length, `${lang}: ${code}`).toBeGreaterThan(0);
      }
  });

  test('every key of the generated set exists in the three languages', () => {
    for (const [lang, texts] of Object.entries(LOCALES))
      for (const code of ERROR_KEYS) expect(typeof texts[code], `${lang}: ${code}`).toBe('string');
  });

  test('messageKey answers theme.errors.<CODE> for known codes', () => {
    expect(messageKey('OUT_OF_STOCK')).toBe('theme.errors.OUT_OF_STOCK');
    expect(messageKey('NETWORK')).toBe('theme.errors.NETWORK');
    expect(messageKey('SESSION_EXPIRED')).toBe('theme.errors.SESSION_EXPIRED');
  });

  test.each([undefined, null, '', 42, 'SOMETHING_NEW', 'INVALID_PROVIDER_SETTINGS', '__proto__'])(
    'an unknown or panel-only code (%p) never shows a raw identifier',
    (code) => {
      expect(messageKey(code)).toBe('theme.errors.GENERIC');
    },
  );

  test('reasonKey falls back to the given code, creditAmountKey to the generic amount text', () => {
    expect(reasonKey('TEST_MODE', 'PAYMENT_METHOD_UNAVAILABLE')).toBe('theme.errors.TEST_MODE');
    expect(reasonKey('WHATEVER', 'PAYMENT_METHOD_UNAVAILABLE')).toBe(
      'theme.errors.PAYMENT_METHOD_UNAVAILABLE',
    );
    expect(reasonKey(undefined)).toBe('theme.errors.GENERIC');

    for (const reason of CREDIT_AMOUNT_REASONS)
      expect(creditAmountKey(reason)).toBe(`theme.errors.INVALID_CREDIT_AMOUNT_${reason}`);
    expect(creditAmountKey('NEW_REASON')).toBe('theme.errors.INVALID_CREDIT_AMOUNT');
  });

  test('placeholders of the interpolated texts are the same in every language', () => {
    const placeholders = (text) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

    for (const code of ERROR_KEYS) {
      const base = placeholders(LOCALES['en-US'][code]);

      for (const lang of ['tr', 'ru']) expect(placeholders(LOCALES[lang][code])).toEqual(base);
    }
  });
});

describe('checkoutAction: one row per code of 14 §10.9', () => {
  const ROWS = [
    ['EMPTY_CART', 'STATE'],
    ['INVALID_CART', 'ALERT'],
    ['INVALID_COUPON', 'CODE'],
    ['INVALID_CREATOR_CODE', 'CODE'],
    ['INVALID_RECIPIENT', 'FIELD'],
    ['MINIMUM_ORDER_AMOUNT_NOT_REACHED', 'ALERT'],
    ['PRICE_CHANGED', 'PRICE_CHANGED'],
    ['INVALID_CREDIT_AMOUNT', 'FIELD'],
    ['CREDITS_DISABLED', 'LEAVE'],
    ['STORE_BUSY', 'RETRY'],
    ['LEGAL_ACCEPTANCE_REQUIRED', 'LEGAL'],
    ['BUYER_INFO_REQUIRED', 'BILLING'],
    ['SHIPPING_ADDRESS_REQUIRED', 'FIELD'],
    ['SHIPPING_UNAVAILABLE', 'ALERT'],
    ['PAYMENT_METHOD_UNAVAILABLE', 'ALERT'],
    ['SUBSCRIPTION_MUST_BE_ALONE', 'ALERT'],
    ['INSUFFICIENT_CREDITS', 'ALERT'],
    ['NOT_LOGGED_IN', 'STATE'],
    ['BUYER_BLOCKED', 'STATE'],
    ['OUT_OF_STOCK', 'ALERT'],
    ['PURCHASE_LIMIT_REACHED', 'ALERT'],
    ['COOLDOWN_ACTIVE', 'ALERT'],
    ['PRODUCT_REQUIREMENT_NOT_MET', 'ALERT'],
    ['IDEMPOTENCY_CONFLICT', 'GENERIC'],
    ['TOO_MANY_REQUESTS', 'RATE_LIMIT'],
    ['PAYMENT_PROVIDER_ERROR', 'ORDER_CREATED'],
    ['INVALID_CSRF_TOKEN', 'RELOAD'],
    ['STORE_DISABLED', 'STATE'],
    ['STORE_UNAVAILABLE', 'STATE'],
    ['NETWORK', 'NETWORK'],
  ];

  test('the table covers exactly the rows of 14 §10.9', () => {
    expect([...CHECKOUT_ACTION_CODES].sort()).toEqual(ROWS.map(([code]) => code).sort());
  });

  test.each(ROWS)('%s -> %s with a message key present in all languages', (code, kind) => {
    const action = checkoutAction(code, {});

    expect(action.kind).toBe(kind);
    expect(action.code).toBe(code);
    expect(action.messageKey).toMatch(/^theme\.(errors|checkout)\./);

    const [, group, name] = action.messageKey.split('.');
    const path = group === 'errors' ? 'errors' : 'checkout';

    for (const lang of ['en-US', 'tr', 'ru']) {
      const root = JSON.parse(
        fs.readFileSync(new URL(`../../../locales/theme/${lang}.json`, import.meta.url), 'utf8'),
      ).theme;

      expect(typeof root[path][name], `${lang}: ${action.messageKey}`).toBe('string');
    }
  });

  test('an unknown code is the generic action and drops the key', () => {
    const action = checkoutAction('SOMETHING_NEW', { error: 'SOMETHING_NEW' });

    expect(action.kind).toBe('GENERIC');
    expect(action.messageKey).toBe('theme.errors.GENERIC');
    expect(action.dropKey).toBe(true);
    expect(checkoutAction(undefined).kind).toBe('GENERIC');
    expect(checkoutAction('toString').kind).toBe('GENERIC');
  });

  test('state actions name the page state', () => {
    expect(checkoutAction('EMPTY_CART').state).toBe('EMPTY');
    expect(checkoutAction('NOT_LOGGED_IN').state).toBe('LOGIN_REQUIRED');
    expect(checkoutAction('BUYER_BLOCKED').state).toBe('BLOCKED');
    expect(checkoutAction('STORE_DISABLED').state).toBe('DISABLED');
    expect(checkoutAction('STORE_UNAVAILABLE').state).toBe('DISABLED');
    expect(isTerminal(checkoutAction('BUYER_BLOCKED'))).toBe(true);
    expect(isTerminal(checkoutAction('OUT_OF_STOCK'))).toBe(false);
  });

  test('the idempotency key is kept for a lost answer, a busy store and a rate limit, dropped otherwise', () => {
    for (const code of ['NETWORK', 'STORE_BUSY', 'TOO_MANY_REQUESTS']) {
      const action = checkoutAction(code);

      expect(action.dropKey).toBe(false);
      expect(action.keepKey).toBe(true);
    }

    for (const code of ['PRICE_CHANGED', 'IDEMPOTENCY_CONFLICT', 'OUT_OF_STOCK', 'INVALID_COUPON'])
      expect(checkoutAction(code).dropKey).toBe(true);
  });

  test('STORE_BUSY retries after 2 seconds', () => {
    expect(checkoutAction('STORE_BUSY').retryAfterMs).toBe(2000);
  });

  test('requote and clear flags of the alert rows', () => {
    expect(checkoutAction('PAYMENT_METHOD_UNAVAILABLE')).toMatchObject({
      clearMethod: true,
      requote: true,
      where: 'payment',
    });
    expect(checkoutAction('INSUFFICIENT_CREDITS')).toMatchObject({
      clearCredits: true,
      requote: true,
      where: 'credits',
    });
    expect(checkoutAction('SHIPPING_UNAVAILABLE')).toMatchObject({
      requote: true,
      where: 'shipping',
    });
    expect(checkoutAction('SUBSCRIPTION_MUST_BE_ALONE').editCart).toBe(true);
    expect(checkoutAction('INVALID_CART')).toMatchObject({ requote: true, scroll: true });
    expect(checkoutAction('INVALID_COUPON')).toMatchObject({ which: 'coupon', dropCode: true });
    expect(checkoutAction('INVALID_CREATOR_CODE')).toMatchObject({
      which: 'creator',
      dropCode: true,
    });
  });

  test('PRICE_CHANGED carries the fresh quote', () => {
    const quote = { total: 12 };
    const action = checkoutAction('PRICE_CHANGED', { error: 'PRICE_CHANGED', quote });

    expect(action.replaceQuote).toBe(true);
    expect(action.details.quote).toBe(quote);
  });

  test('details copy only the known extras', () => {
    const action = checkoutAction('MINIMUM_ORDER_AMOUNT_NOT_REACHED', {
      minimum: 5,
      secret: 'x',
      retryAfter: 3,
    });

    expect(action.details).toEqual({ minimum: 5, retryAfter: 3 });
  });

  test('PAYMENT_METHOD_UNAVAILABLE shows its reason (TEST_MODE) and falls back to the generic method text', () => {
    expect(checkoutAction('PAYMENT_METHOD_UNAVAILABLE', { reason: 'TEST_MODE' }).messageKey).toBe(
      'theme.errors.TEST_MODE',
    );
    expect(checkoutAction('PAYMENT_METHOD_UNAVAILABLE', { reason: 'ODD' }).messageKey).toBe(
      'theme.errors.PAYMENT_METHOD_UNAVAILABLE',
    );
  });

  test('coupon reasons choose the text, with the code error as fallback', () => {
    expect(checkoutAction('INVALID_COUPON', { reason: 'CODE_EXPIRED' }).messageKey).toBe(
      'theme.errors.CODE_EXPIRED',
    );
    expect(checkoutAction('INVALID_COUPON', {}).messageKey).toBe('theme.errors.INVALID_COUPON');
    expect(checkoutAction('INVALID_CREATOR_CODE', { reason: 'CODE_NOT_FOUND' }).messageKey).toBe(
      'theme.errors.CODE_NOT_FOUND',
    );
    expect(checkoutAction('INVALID_CREATOR_CODE', {}).messageKey).toBe(
      'theme.errors.INVALID_CREATOR_CODE',
    );
  });

  test('top-up amount reasons have their own text', () => {
    expect(
      checkoutAction('INVALID_CREDIT_AMOUNT', { reason: 'BELOW_MINIMUM', min: 5, max: 100 })
        .messageKey,
    ).toBe('theme.errors.INVALID_CREDIT_AMOUNT_BELOW_MINIMUM');
  });

  test('INVALID_CSRF_TOKEN reads as an expired session and NETWORK as the unconfirmed order text', () => {
    expect(checkoutAction('INVALID_CSRF_TOKEN').messageKey).toBe('theme.errors.SESSION_EXPIRED');
    expect(checkoutAction('NETWORK').messageKey).toBe('theme.checkout.network');
    expect(checkoutAction('IDEMPOTENCY_CONFLICT').messageKey).toBe('theme.errors.GENERIC');
  });
});
