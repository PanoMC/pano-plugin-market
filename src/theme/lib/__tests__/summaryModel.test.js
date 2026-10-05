import { describe, expect, test } from 'bun:test';
import {
  chargedInCurrency,
  codeState,
  codesHidden,
  lineErrors,
  nextCodeState,
  summaryAlerts,
  summaryLines,
  summaryRows,
} from '../summaryModel.js';

const base = (extra = {}) => ({
  currency: 'USD',
  subtotal: 100,
  discountTotal: 0,
  upgradeDiscount: 0,
  couponDiscount: 0,
  creatorDiscount: 0,
  shippingTotal: 0,
  paymentFee: 0,
  vatTotal: 0,
  total: 100,
  gatewayAmount: 100,
  requiresShipping: false,
  ...extra,
});

const ids = (quote) => summaryRows(quote).map((row) => row.id);

describe('summaryRows', () => {
  test('a plain quote shows subtotal and total only', () => {
    expect(ids(base())).toEqual(['subtotal', 'total']);
  });

  test('every optional row appears only when its server value is positive', () => {
    const quote = base({
      discountTotal: 10,
      upgradeDiscount: 5,
      couponDiscount: 3,
      coupon: { code: 'SAVE' },
      creatorDiscount: 2,
      creatorCode: { code: 'STEVE' },
      requiresShipping: true,
      shippingMethodId: 'std',
      shippingTotal: 4,
      paymentFee: 1,
      vatTotal: 12,
      total: 90,
      gatewayAmount: 60,
      credits: { applied: 30, appliedValue: 30, name: 'Coins' },
    });

    expect(ids(quote)).toEqual([
      'subtotal',
      'discounts',
      'upgrade',
      'coupon',
      'creator',
      'shipping',
      'fee',
      'vat',
      'total',
      'credits',
      'to-pay',
    ]);
  });

  test('discount rows are negative and carry the server number untouched', () => {
    const rows = summaryRows(
      base({ discountTotal: 10.5, couponDiscount: 2.25, coupon: { code: 'X' } }),
    );
    const discounts = rows.find((r) => r.id === 'discounts');
    const coupon = rows.find((r) => r.id === 'coupon');

    expect(discounts).toMatchObject({ amount: 10.5, negative: true });
    expect(coupon).toMatchObject({ amount: 2.25, negative: true, values: { code: 'X' } });
  });

  test('the total is the quote total, never a sum of the rows', () => {
    // rows deliberately do not add up: the theme must show total as given
    const quote = base({ subtotal: 100, discountTotal: 10, total: 1234.56 });
    const total = summaryRows(quote).find((r) => r.id === 'total');

    expect(total.amount).toBe(1234.56);
    expect(total.strong).toBe(true);
  });

  test('shipping shows a dash until a method is chosen', () => {
    const open = summaryRows(base({ requiresShipping: true, shippingTotal: 0 }));
    const chosen = summaryRows(
      base({ requiresShipping: true, shippingMethodId: 'x', shippingTotal: 0 }),
    );

    expect(open.find((r) => r.id === 'shipping').amount).toBeNull();
    expect(chosen.find((r) => r.id === 'shipping').amount).toBe(0);
  });

  test('VAT label depends on whether prices include it', () => {
    expect(
      summaryRows(base({ vatTotal: 5, pricesIncludeVat: true })).find((r) => r.id === 'vat')
        .labelKey,
    ).toBe('theme.checkout.vat-included');
    expect(
      summaryRows(base({ vatTotal: 5, pricesIncludeVat: false })).find((r) => r.id === 'vat')
        .labelKey,
    ).toBe('theme.checkout.vat-added');
  });

  test('credits row shows the value with the credits in the extra; to-pay only when it differs from the total', () => {
    const quote = base({
      total: 100,
      gatewayAmount: 100,
      credits: { applied: 25, appliedValue: 25, name: 'Coins' },
    });
    const rows = summaryRows(quote);

    expect(rows.find((r) => r.id === 'credits')).toMatchObject({
      amount: 25,
      negative: true,
      extra: { credits: 25, name: 'Coins' },
    });
    expect(rows.some((r) => r.id === 'to-pay')).toBe(false);
  });

  test('to-pay shows the gateway amount when credits paid part of the order', () => {
    const rows = summaryRows(base({ gatewayAmount: 75 }));

    expect(rows.at(-1)).toMatchObject({ id: 'to-pay', amount: 75, strong: true });
  });

  test('to-pay of zero is shown (the order is paid in credits)', () => {
    expect(summaryRows(base({ gatewayAmount: 0 })).at(-1)).toMatchObject({
      id: 'to-pay',
      amount: 0,
    });
  });

  test.each([[null], [undefined], ['x'], [[]], [5]])(
    'a non-object quote (%p) has no rows',
    (quote) => {
      expect(summaryRows(quote)).toEqual([]);
    },
  );
});

describe('chargedInCurrency', () => {
  test('is the order currency when it differs from the display currency', () => {
    expect(chargedInCurrency({ currency: 'USD', displayCurrency: 'TRY' })).toBe('USD');
  });

  test.each([
    [{ currency: 'USD', displayCurrency: 'USD' }],
    [{ currency: 'USD' }],
    [{ currency: 'USD', displayCurrency: '' }],
    [null],
  ])('is null for %p', (quote) => {
    expect(chargedInCurrency(quote)).toBeNull();
  });
});

describe('summaryAlerts', () => {
  test('maps the level to a Bootstrap alert class and the code to its key', () => {
    const alerts = summaryAlerts({
      messages: [
        { code: 'SUBSCRIPTION_MUST_BE_ALONE', level: 'error' },
        { code: 'CREDITS_REDUCED', level: 'warning' },
        { code: 'TEST_MODE', level: 'info' },
      ],
    });

    expect(alerts.map((a) => [a.code, a.cls, a.messageKey])).toEqual([
      ['SUBSCRIPTION_MUST_BE_ALONE', 'alert-danger', 'theme.errors.SUBSCRIPTION_MUST_BE_ALONE'],
      ['CREDITS_REDUCED', 'alert-warning', 'theme.errors.CREDITS_REDUCED'],
      ['TEST_MODE', 'alert-info', 'theme.errors.TEST_MODE'],
    ]);
  });

  test('an unknown code reads as the generic text', () => {
    const [alert] = summaryAlerts({ messages: [{ code: 'NEW_THING', level: 'error' }] });

    expect(alert.messageKey).toBe('theme.errors.GENERIC');
  });

  test('line messages and messages shown elsewhere are left out', () => {
    const alerts = summaryAlerts({
      messages: [
        { code: 'OUT_OF_STOCK', level: 'error', lineKey: 'a' },
        { code: 'RECIPIENT_UNKNOWN', level: 'warning' },
        { code: 'SHIPPING_ADDRESS_REQUIRED', level: 'error' },
        { code: 'SHIPPING_UNAVAILABLE', level: 'error' },
        { code: 'EXTERNAL_PRICING', level: 'info' },
        { code: 'BUYER_BLOCKED', level: 'error' },
        { code: 'CODE_EXPIRED', level: 'warning' },
        { code: 'MINIMUM_ORDER_AMOUNT_NOT_REACHED', level: 'error' },
      ],
      coupon: { code: 'X', valid: false, reason: 'CODE_EXPIRED' },
      minimumOrderAmount: 20,
    });

    expect(alerts.map((a) => a.code)).toEqual(['MINIMUM_ORDER_AMOUNT_NOT_REACHED']);
    expect(alerts[0].values).toEqual({ minimum: 20 });
  });

  test('a warning about a blocked buyer is still shown', () => {
    expect(summaryAlerts({ messages: [{ code: 'BUYER_BLOCKED', level: 'warning' }] })).toHaveLength(
      1,
    );
  });

  test('numeric extras become interpolation values, text extras are dropped', () => {
    const [alert] = summaryAlerts({
      messages: [{ code: 'COOLDOWN_ACTIVE', level: 'error', retryAfter: 30, minimum: '<b>x</b>' }],
    });

    expect(alert.values).toEqual({ retryAfter: 30 });
  });

  test.each([null, {}, { messages: 'x' }, { messages: [null, 7, {}] }])('tolerates %p', (quote) => {
    expect(summaryAlerts(quote)).toEqual([]);
  });
});

describe('lines', () => {
  test('lineErrors keeps string codes with their keys', () => {
    expect(lineErrors({ errors: ['OUT_OF_STOCK', 7, 'ODD_ONE'] })).toEqual([
      { code: 'OUT_OF_STOCK', messageKey: 'theme.errors.OUT_OF_STOCK' },
      { code: 'ODD_ONE', messageKey: 'theme.errors.GENERIC' },
    ]);
    expect(lineErrors({})).toEqual([]);
  });

  test('bundle children are listed under their parent only', () => {
    const lines = [
      { lineKey: 'a', kind: 'BUNDLE' },
      { lineKey: 'b', kind: 'BUNDLE_CHILD', parentLineKey: 'a' },
      { lineKey: 'c', kind: 'PRODUCT' },
    ];

    expect(summaryLines({ lines }).map((l) => l.lineKey)).toEqual(['a', 'c']);
    expect(summaryLines(null)).toEqual([]);
  });
});

describe('codeState', () => {
  test('valid with an applied code is APPLIED', () => {
    expect(codeState({ code: 'SAVE', valid: true }, 'INVALID_COUPON', 'SAVE')).toEqual({
      status: 'APPLIED',
      code: 'SAVE',
    });
  });

  test('valid without a code in the draft is idle', () => {
    expect(codeState({ code: 'SAVE', valid: true }, 'INVALID_COUPON', '').status).toBe('IDLE');
  });

  test('invalid shows the reason, falling back to the code error', () => {
    expect(codeState({ valid: false, reason: 'CODE_EXPIRED' }, 'INVALID_COUPON', 'X')).toEqual({
      status: 'INVALID',
      reason: 'CODE_EXPIRED',
      messageKey: 'theme.errors.CODE_EXPIRED',
    });
    expect(codeState({ valid: false, reason: null }, 'INVALID_CREATOR_CODE', 'X').messageKey).toBe(
      'theme.errors.INVALID_CREATOR_CODE',
    );
    expect(codeState({ valid: false, reason: 'MADE_UP' }, 'INVALID_COUPON', 'X').messageKey).toBe(
      'theme.errors.INVALID_COUPON',
    );
  });

  test('locked attempts carry a countdown, 60 s when the server did not say', () => {
    expect(
      codeState({ valid: false, reason: 'CODE_ATTEMPTS_LOCKED', retryAfter: 45 }, 'X', 'A'),
    ).toMatchObject({
      status: 'LOCKED',
      retryAfter: 45,
    });
    expect(codeState({ valid: false, reason: 'CODE_ATTEMPTS_LOCKED' }, 'X', 'A').retryAfter).toBe(
      60,
    );
  });

  test.each([null, undefined, 'x'])('no result (%p) is idle', (result) => {
    expect(codeState(result, 'INVALID_COUPON', 'X')).toEqual({ status: 'IDLE' });
  });
});

describe('nextCodeState', () => {
  const idle = { status: 'IDLE' };

  test('an invalid code is dropped from the draft and the error is shown', () => {
    const next = nextCodeState({
      current: idle,
      result: { valid: false, reason: 'CODE_NOT_FOUND' },
      applied: 'BAD',
      fallbackCode: 'INVALID_COUPON',
    });

    expect(next.drop).toBe(true);
    expect(next.ui).toMatchObject({ status: 'INVALID', messageKey: 'theme.errors.CODE_NOT_FOUND' });
  });

  test('the error outlives the code: a later quote without the code keeps it', () => {
    const invalid = { status: 'INVALID', messageKey: 'theme.errors.CODE_NOT_FOUND' };
    const next = nextCodeState({
      current: invalid,
      result: null,
      applied: '',
      fallbackCode: 'INVALID_COUPON',
    });

    expect(next).toEqual({ ui: invalid, drop: false });
  });

  test('typing a new code clears a shown error until the quote answers', () => {
    const invalid = { status: 'INVALID', messageKey: 'x' };
    const next = nextCodeState({
      current: invalid,
      result: null,
      applied: 'NEW',
      fallbackCode: 'INVALID_COUPON',
    });

    expect(next.ui).toEqual({ status: 'IDLE' });
  });

  test('a valid code is applied and stays in the draft', () => {
    const next = nextCodeState({
      current: idle,
      result: { code: 'SAVE', valid: true },
      applied: 'SAVE',
      fallbackCode: 'INVALID_COUPON',
    });

    expect(next).toEqual({ ui: { status: 'APPLIED', code: 'SAVE' }, drop: false });
  });

  test('a locked code is dropped and the lock ends retryAfter seconds from now', () => {
    const next = nextCodeState({
      current: idle,
      result: { valid: false, reason: 'CODE_ATTEMPTS_LOCKED', retryAfter: 30 },
      applied: 'X',
      fallbackCode: 'INVALID_COUPON',
      nowMs: 1_000,
    });

    expect(next.drop).toBe(true);
    expect(next.ui).toMatchObject({ status: 'LOCKED', until: 31_000 });
  });
});

describe('codesHidden', () => {
  test('hidden in top-up mode and for EXTERNAL pricing only', () => {
    expect(codesHidden({ topup: 10 })).toBe(true);
    expect(codesHidden({ topup: null, method: { pricing: 'EXTERNAL' } })).toBe(true);
    expect(codesHidden({ topup: null, method: { pricing: 'EXTERNAL_TAX' } })).toBe(false);
    expect(codesHidden({ topup: null, method: { pricing: 'MARKET' } })).toBe(false);
    expect(codesHidden({ topup: null })).toBe(false);
  });
});
