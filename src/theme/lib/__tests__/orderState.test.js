import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import {
  CONFIRMING_WINDOW_MS,
  SETTLED_STATES,
  VIEW_STATES,
  addressLines,
  deliveryBadge,
  expiryLeft,
  fieldPairs,
  hasOrderParams,
  invoiceNeedsBlob,
  invoicePath,
  isUsableToken,
  orderExtras,
  parseOrderId,
  parseReturnHint,
  resolveOrderLoad,
  safeTrackingUrl,
  shipmentBadge,
  stripOrderParams,
  tokenAfterRefetch,
  totalsRows,
  viewState,
} from '../orderState.js';

const ID = 'AbCdEfGhIjKlMnOpQrSt';

const order = (over = {}) => ({
  publicId: ID,
  number: 42,
  status: 'COMPLETED',
  fulfillmentStatus: 'NONE',
  shippingStatus: 'NOT_REQUIRED',
  limited: false,
  currency: 'EUR',
  payment: { methodId: 'stripe', status: 'SUCCEEDED', start: null },
  ...over,
});

const pending = (ps, over = {}) =>
  order({
    status: 'PENDING',
    payment: { methodId: 'stripe', status: ps, start: null },
    ...over,
  });

describe('viewState: one case per row of 14 §11.3', () => {
  test('1 PAID_DELIVERED: COMPLETED, NONE, NOT_REQUIRED', () => {
    const v = viewState(order());
    expect(v.state).toBe('PAID_DELIVERED');
    expect(v.variant).toBe('success');
    expect(v.icon).toBe('fa-solid fa-circle-check');
    expect(v.titleKey).toBe('theme.order.state.paid');
    expect(v.panels).toMatchObject({ items: true, totals: true, shipments: true, invoice: true });
    expect(v.panels.payment).toBe(false);
    expect(v.settled).toBe(true);
  });

  test('1 PAID_DELIVERED: PARTIALLY_REFUNDED, FULFILLED, DELIVERED', () => {
    expect(
      viewState(
        order({
          status: 'PARTIALLY_REFUNDED',
          fulfillmentStatus: 'FULFILLED',
          shippingStatus: 'DELIVERED',
        }),
      ).state,
    ).toBe('PAID_DELIVERED');
  });

  test.each(['PENDING', 'PARTIAL', 'SHIPPED', 'RETURNED'])('2 PAID_SHIPPING: %s', (shipping) => {
    const v = viewState(order({ shippingStatus: shipping, fulfillmentStatus: 'FULFILLED' }));
    expect(v.state).toBe('PAID_SHIPPING');
    expect(v.variant).toBe('success');
    expect(v.titleKey).toBe(`theme.order.state.shipping.${shipping}`);
    expect(v.settled).toBe(false);
    expect(v.panels.shipments).toBe(true);
  });

  test.each(['PENDING', 'PARTIAL'])('3 PAID_DELIVERING: fulfillment %s', (f) => {
    const v = viewState(order({ fulfillmentStatus: f }));
    expect(v.state).toBe('PAID_DELIVERING');
    expect(v.lineKey).toBe('theme.order.state.delivering');
    expect(v.titleKey).toBe('theme.order.state.paid');
    expect(v.settled).toBe(false);
  });

  test.each([
    ['FAILED', 'theme.order.state.delivery-failed'],
    ['REVOKED', 'theme.order.state.revoked'],
  ])('4 PAID_DELIVERY_FAILED: %s', (f, key) => {
    const v = viewState(order({ fulfillmentStatus: f }));
    expect(v.state).toBe('PAID_DELIVERY_FAILED');
    expect(v.variant).toBe('warning');
    expect(v.titleKey).toBe(key);
    expect(v.settled).toBe(true);
  });

  test('5 REVIEW', () => {
    const v = viewState(order({ status: 'REVIEW' }));
    expect(v.state).toBe('REVIEW');
    expect(v.variant).toBe('info');
    expect(v.icon).toBe('fa-solid fa-hourglass-half');
    expect(v.panels).toMatchObject({ items: true, totals: true, payment: false, invoice: false });
  });

  test('6 PROCESSING: payment attempt PROCESSING, read-only instructions', () => {
    const v = viewState(pending('PROCESSING'));
    expect(v.state).toBe('PROCESSING');
    expect(v.spinner).toBe(true);
    expect(v.titleKey).toBe('theme.order.state.processing');
    expect(v.panels).toMatchObject({ instructions: true, payment: false, cancel: false });
  });

  test('6 PROCESSING: bank transfer uses processing-bank', () => {
    const o = pending('PROCESSING');
    o.payment.methodId = 'bank-transfer';
    expect(viewState(o).titleKey).toBe('theme.order.state.processing-bank');
  });

  test.each([
    ['success', 'CREATED'],
    ['success', 'PENDING'],
    ['pending', 'CREATED'],
    ['pending', 'PENDING'],
  ])('7 CONFIRMING: hint %s, attempt %s, within 60 s', (hint, ps) => {
    const v = viewState(pending(ps), hint, 1_000_000 + 30_000, 1_000_000);
    expect(v.state).toBe('CONFIRMING');
    expect(v.spinner).toBe(true);
    expect(v.titleKey).toBe('theme.order.state.confirming');
    expect(v.panels.payment).toBe(false);
    expect(v.settled).toBe(false);
  });

  test('8 AWAITING_PAYMENT: plain pending order', () => {
    const v = viewState(pending('PENDING'));
    expect(v.state).toBe('AWAITING_PAYMENT');
    expect(v.variant).toBe('warning');
    expect(v.icon).toBe('fa-solid fa-credit-card');
    expect(v.showCountdown).toBe(true);
    expect(v.subKey).toBeNull();
    expect(v.panels).toMatchObject({ payment: true, items: true, totals: true, cancel: true });
  });

  test('8 AWAITING_PAYMENT: return=cancel sub-line', () => {
    expect(viewState(pending('PENDING'), 'cancel').subKey).toBe(
      'theme.order.state.cancelled-at-gateway',
    );
  });

  test.each(['FAILED', 'EXPIRED'])('8 AWAITING_PAYMENT: attempt %s sub-line', (ps) => {
    const v = viewState(pending(ps));
    expect(v.state).toBe('AWAITING_PAYMENT');
    expect(v.subKey).toBe('theme.order.state.attempt-failed');
  });

  test('8 AWAITING_PAYMENT: a CANCELLED attempt has no failure sub-line', () => {
    expect(viewState(pending('CANCELLED')).subKey).toBeNull();
  });

  test('9 REFUNDED', () => {
    const v = viewState(order({ status: 'REFUNDED' }));
    expect(v.state).toBe('REFUNDED');
    expect(v.variant).toBe('secondary');
    expect(v.icon).toBe('fa-solid fa-rotate-left');
    expect(v.panels).toMatchObject({ items: true, totals: true, invoice: true, shipments: false });
  });

  test('10 CHARGEBACK', () => {
    const v = viewState(order({ status: 'CHARGEBACK' }));
    expect(v.state).toBe('CHARGEBACK');
    expect(v.variant).toBe('danger');
    expect(v.panels.invoice).toBe(false);
  });

  test.each([
    ['FAILED', 'danger'],
    ['CANCELLED', 'secondary'],
    ['EXPIRED', 'secondary'],
  ])('11 %s with a link back to the store', (status, variant) => {
    const v = viewState(order({ status }));
    expect(v.state).toBe(status);
    expect(v.variant).toBe(variant);
    expect(v.titleKey).toBe(`theme.order.state.${status}`);
    expect(v.backToStore).toBe(true);
    expect(v.panels).toMatchObject({ items: true, totals: true, payment: false });
  });

  test('every state of the table is produced and the settled set is exactly the documented one', () => {
    expect(VIEW_STATES).toHaveLength(13);
    expect(SETTLED_STATES.sort()).toEqual(
      [
        'PAID_DELIVERED',
        'PAID_DELIVERY_FAILED',
        'REFUNDED',
        'CHARGEBACK',
        'FAILED',
        'CANCELLED',
        'EXPIRED',
      ].sort(),
    );
  });

  test('an unknown status is a neutral block without payment panel', () => {
    const v = viewState(order({ status: 'SOMETHING_NEW' }));
    expect(v.state).toBe('UNKNOWN');
    expect(v.panels.payment).toBe(false);
    expect(v.settled).toBe(true);
  });
});

describe('viewState: precedence and edge cases', () => {
  test('COMPLETED + fulfillment PENDING + shipping PENDING => PAID_DELIVERING (not PAID_SHIPPING)', () => {
    expect(
      viewState(order({ fulfillmentStatus: 'PENDING', shippingStatus: 'PENDING' })).state,
    ).toBe('PAID_DELIVERING');
  });

  test('fulfillment FAILED wins over a pending shipment', () => {
    expect(viewState(order({ fulfillmentStatus: 'FAILED', shippingStatus: 'PENDING' })).state).toBe(
      'PAID_DELIVERY_FAILED',
    );
  });

  test('fulfillment REVOKED with SHIPPED goods is still the failure row', () => {
    expect(
      viewState(order({ fulfillmentStatus: 'REVOKED', shippingStatus: 'SHIPPED' })).state,
    ).toBe('PAID_DELIVERY_FAILED');
  });

  test('NONE fulfillment with a pending shipment is shipping, not delivered', () => {
    expect(viewState(order({ shippingStatus: 'PENDING' })).state).toBe('PAID_SHIPPING');
  });

  test('PROCESSING wins over CONFIRMING (row 6 before row 7)', () => {
    expect(viewState(pending('PROCESSING'), 'success', 1_010_000, 1_000_000).state).toBe(
      'PROCESSING',
    );
  });

  test('CONFIRMING falls back to AWAITING_PAYMENT after 60 s (re-evaluated from the clock)', () => {
    const o = pending('PENDING');
    const mounted = 5_000_000;
    expect(viewState(o, 'success', mounted + CONFIRMING_WINDOW_MS - 1, mounted).state).toBe(
      'CONFIRMING',
    );
    expect(viewState(o, 'success', mounted + CONFIRMING_WINDOW_MS, mounted).state).toBe(
      'AWAITING_PAYMENT',
    );
    expect(viewState(o, 'success', mounted + 120_000, mounted).state).toBe('AWAITING_PAYMENT');
  });

  test('CONFIRMING needs an attempt that is still CREATED or PENDING', () => {
    expect(viewState(pending('FAILED'), 'success', 1, 1).state).toBe('AWAITING_PAYMENT');
    expect(viewState(pending('EXPIRED'), 'pending').state).toBe('AWAITING_PAYMENT');
  });

  test('CONFIRMING needs a success or pending hint; cancel and none do not qualify', () => {
    expect(viewState(pending('PENDING'), 'cancel').state).toBe('AWAITING_PAYMENT');
    expect(viewState(pending('PENDING'), null).state).toBe('AWAITING_PAYMENT');
  });

  test('before mount (clock 0 / unknown mount) CONFIRMING shows, so SSR and hydration agree', () => {
    expect(viewState(pending('PENDING'), 'success', 0, 0).state).toBe('CONFIRMING');
    expect(viewState(pending('PENDING'), 'success', 9_999_999_999, 0).state).toBe('CONFIRMING');
  });

  test('returnHint is ignored for a paid, refunded or failed order', () => {
    for (const hint of ['success', 'cancel', 'pending']) {
      expect(viewState(order(), hint).state).toBe('PAID_DELIVERED');
      expect(viewState(order({ status: 'REFUNDED' }), hint).state).toBe('REFUNDED');
      expect(viewState(order({ status: 'FAILED' }), hint).state).toBe('FAILED');
    }
  });

  test('a pending order without a payment object is AWAITING_PAYMENT', () => {
    expect(viewState(order({ status: 'PENDING', payment: null })).state).toBe('AWAITING_PAYMENT');
  });

  test('missing fulfillment / shipping members take the documented defaults', () => {
    expect(viewState({ status: 'COMPLETED' }).state).toBe('PAID_DELIVERED');
  });

  test('limited flag passes through and reduces the panels to items and totals in every state', () => {
    for (const o of [
      order({ limited: true }),
      order({ limited: true, shippingStatus: 'SHIPPED' }),
      order({ limited: true, status: 'PENDING', payment: null }),
      order({ limited: true, status: 'REFUNDED' }),
    ]) {
      const v = viewState(o);
      expect(v.limited).toBe(true);
      expect(v.panels).toEqual({
        items: true,
        totals: true,
        shipments: false,
        invoice: false,
        payment: false,
        instructions: false,
        cancel: false,
      });
    }
    // the state itself is unchanged by the flag
    expect(viewState(order({ limited: true })).state).toBe('PAID_DELIVERED');
    expect(viewState(order()).limited).toBe(false);
  });
});

describe('every key a view state can produce exists in the three locales', () => {
  const locales = Object.fromEntries(
    ['en-US', 'tr', 'ru'].map((lang) => [
      lang,
      JSON.parse(
        fs.readFileSync(new URL(`../../../locales/theme/${lang}.json`, import.meta.url), 'utf8'),
      ),
    ]),
  );
  const resolve = (tree, key) => key.split('.').reduce((node, part) => node?.[part], tree);

  const orders = [];
  for (const status of [
    'COMPLETED',
    'PARTIALLY_REFUNDED',
    'REVIEW',
    'PENDING',
    'REFUNDED',
    'CHARGEBACK',
    'FAILED',
    'CANCELLED',
    'EXPIRED',
    'WHATEVER',
  ])
    for (const fulfillmentStatus of [
      'NONE',
      'PENDING',
      'PARTIAL',
      'FULFILLED',
      'FAILED',
      'REVOKED',
    ])
      for (const shippingStatus of [
        'NOT_REQUIRED',
        'PENDING',
        'PARTIAL',
        'SHIPPED',
        'DELIVERED',
        'RETURNED',
      ])
        for (const ps of ['CREATED', 'PENDING', 'PROCESSING', 'FAILED', 'EXPIRED', 'CANCELLED'])
          for (const methodId of ['stripe', 'bank-transfer'])
            orders.push({
              status,
              fulfillmentStatus,
              shippingStatus,
              payment: { methodId, status: ps },
            });

  test('title, line and sub keys of the whole matrix resolve to non-empty text', () => {
    const keys = new Set();
    const states = new Set();
    for (const o of orders)
      for (const hint of [null, 'success', 'cancel', 'pending']) {
        const v = viewState(o, hint, 1, 1);
        states.add(v.state);
        for (const key of [v.titleKey, v.lineKey, v.subKey]) if (key) keys.add(key);
      }

    expect([...states].sort()).toEqual([...VIEW_STATES, 'UNKNOWN'].sort());
    expect(keys.size).toBeGreaterThan(18);

    for (const lang of Object.keys(locales))
      for (const key of keys) {
        const text = resolve(locales[lang], key);
        expect(typeof text, `${lang} ${key}`).toBe('string');
        expect(text.length, `${lang} ${key}`).toBeGreaterThan(0);
      }
  });

  test('delivery, shipment and totals keys resolve in the three locales', () => {
    const keys = [
      ...['PENDING', 'PARTIAL', 'FULFILLED', 'FAILED', 'REVOKED'].map(
        (d) => deliveryBadge(d).labelKey,
      ),
      ...[
        'CREATED',
        'LABEL_READY',
        'IN_TRANSIT',
        'OUT_FOR_DELIVERY',
        'DELIVERED',
        'EXCEPTION',
        'RETURNING',
        'RETURNED',
        'CANCELLED',
        'LOST',
        'nope',
      ].map((s) => shipmentBadge(s).labelKey),
      ...totalsRows(
        order({
          totals: {
            subtotal: 1,
            discountTotal: 1,
            upgradeDiscount: 1,
            couponDiscount: 1,
            creatorDiscount: 1,
            shippingTotal: 1,
            paymentFee: 1,
            vatTotal: 1,
            total: 1,
            creditAmount: 1,
            creditValue: 1,
            gatewayAmount: 0,
            refundedTotal: 1,
          },
        }),
        { pricesIncludeVat: false },
      ).map((r) => r.labelKey),
      ...totalsRows(order({ totals: { vatTotal: 1 } }), { pricesIncludeVat: true }).map(
        (r) => r.labelKey,
      ),
    ];

    for (const lang of Object.keys(locales))
      for (const key of keys)
        expect(typeof resolve(locales[lang], key), `${lang} ${key}`).toBe('string');
  });
});

describe('orderExtras', () => {
  test('refund pending, test mode, gift and a safe buyer action', () => {
    expect(
      orderExtras(
        order({
          refundPending: true,
          testMode: true,
          isGift: true,
          recipientUsername: 'Alex',
          buyerActionUrl: 'https://bank.example/confirm',
        }),
      ),
    ).toEqual({
      refundPending: true,
      buyerActionUrl: 'https://bank.example/confirm',
      testMode: true,
      isGift: true,
      recipientUsername: 'Alex',
    });
  });

  test('javascript:, data: and relative buyer action URLs are dropped', () => {
    for (const url of ['javascript:alert(1)', 'data:text/html,x', '/relative', '//evil.test', ''])
      expect(orderExtras(order({ buyerActionUrl: url })).buyerActionUrl).toBeNull();
  });

  test('a limited view never shows the refund line or the buyer action', () => {
    const extras = orderExtras(
      order({ limited: true, refundPending: true, buyerActionUrl: 'https://x.test/a' }),
    );
    expect(extras.refundPending).toBe(false);
    expect(extras.buyerActionUrl).toBeNull();
  });

  test('defaults', () => {
    expect(orderExtras(order())).toEqual({
      refundPending: false,
      buyerActionUrl: null,
      testMode: false,
      isGift: false,
      recipientUsername: null,
    });
  });
});

describe('totalsRows', () => {
  const full = {
    subtotal: 100,
    discountTotal: 10,
    couponDiscount: 5,
    creatorDiscount: 2,
    upgradeDiscount: 3,
    shippingTotal: 4.99,
    paymentFee: 1,
    vatTotal: 19,
    total: 99,
    creditAmount: 500,
    creditValue: 5,
    gatewayAmount: 94,
    refundedTotal: 20,
  };

  test('every row carries the server number untouched, in display order', () => {
    const rows = totalsRows(order({ totals: full, shipping: { methodName: 'Post' } }));
    expect(rows.map((r) => r.id)).toEqual([
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
      'refunded',
    ]);
    expect(rows.find((r) => r.id === 'total')).toMatchObject({ amount: 99, strong: true });
    expect(rows.find((r) => r.id === 'credits')).toMatchObject({
      amount: 5,
      negative: true,
      extra: { credits: 500, name: '' },
    });
    expect(rows.find((r) => r.id === 'refunded')).toMatchObject({ amount: 20, negative: true });
  });

  test('the theme never computes: numbers that do not add up are shown as given', () => {
    const rows = totalsRows(order({ totals: { subtotal: 1, discountTotal: 1, total: 777 } }));
    expect(rows.find((r) => r.id === 'total').amount).toBe(777);
    expect(rows.map((r) => r.amount)).toEqual([1, 1, 777]);
  });

  test('zero rows are left out, subtotal and total stay', () => {
    const rows = totalsRows(
      order({ totals: { subtotal: 5, discountTotal: 0, paymentFee: 0, vatTotal: 0, total: 5 } }),
    );
    expect(rows.map((r) => r.id)).toEqual(['subtotal', 'total']);
  });

  test('shipping row only for a shipped order or a charged shipping total', () => {
    expect(
      totalsRows(order({ totals: { subtotal: 1, shippingTotal: 0, total: 1 } })).map((r) => r.id),
    ).toEqual(['subtotal', 'total']);
    expect(
      totalsRows(
        order({
          shipping: { methodName: 'Post' },
          totals: { subtotal: 1, shippingTotal: 0, total: 1 },
        }),
      ).map((r) => r.id),
    ).toEqual(['subtotal', 'shipping', 'total']);
  });

  test('VAT label follows pricesIncludeVat', () => {
    const o = order({ totals: { vatTotal: 3, total: 10 } });
    expect(totalsRows(o, { pricesIncludeVat: true })[0].labelKey).toBe(
      'theme.checkout.vat-included',
    );
    expect(totalsRows(o)[0].labelKey).toBe('theme.checkout.vat-added');
  });

  test('credits name comes from order.credits', () => {
    const rows = totalsRows(
      order({
        credits: { name: 'Coins' },
        totals: { total: 10, creditAmount: 4, creditValue: 4, gatewayAmount: 6 },
      }),
    );
    expect(rows.find((r) => r.id === 'credits').extra.name).toBe('Coins');
  });

  test('a limited view (only total) shows one row; a recipient view (no totals) none', () => {
    expect(totalsRows(order({ limited: true, totals: { total: 12.5 } }))).toEqual([
      { id: 'total', labelKey: 'theme.order.totals.total', amount: 12.5, strong: true },
    ]);
    expect(totalsRows(order({ totals: null }))).toEqual([]);
    expect(totalsRows({})).toEqual([]);
  });
});

describe('items and shipments', () => {
  test('delivery badges: NONE hidden, DELIVERED read as FULFILLED', () => {
    expect(deliveryBadge('NONE')).toBeNull();
    expect(deliveryBadge(undefined)).toBeNull();
    expect(deliveryBadge('PENDING').cls).toBe('text-bg-warning');
    expect(deliveryBadge('PARTIAL').cls).toBe('text-bg-info');
    expect(deliveryBadge('FULFILLED').cls).toBe('text-bg-success');
    expect(deliveryBadge('DELIVERED').cls).toBe('text-bg-success');
    expect(deliveryBadge('FAILED').cls).toBe('text-bg-danger');
    expect(deliveryBadge('REVOKED').cls).toBe('text-bg-secondary');
  });

  test('fieldPairs from an object, an array, with empties and objects dropped', () => {
    expect(
      fieldPairs({ Nick: 'Steve', Empty: '', Nothing: null, Obj: { a: 1 }, N: 3, B: true }),
    ).toEqual([
      { label: 'Nick', value: 'Steve' },
      { label: 'N', value: '3' },
      { label: 'B', value: 'true' },
    ]);
    expect(
      fieldPairs([
        { label: 'Size', value: 'XL' },
        { name: 'Color', value: 'red' },
        { key: 'k', value: '' },
        'garbage',
      ]),
    ).toEqual([
      { label: 'Size', value: 'XL' },
      { label: 'Color', value: 'red' },
    ]);
    expect(fieldPairs(null)).toEqual([]);
    expect(fieldPairs('x')).toEqual([]);
  });

  test('shipment badge per status, unknown reads as UNKNOWN', () => {
    expect(shipmentBadge('DELIVERED')).toEqual({
      cls: 'text-bg-success',
      labelKey: 'theme.order.shipment.DELIVERED',
    });
    expect(shipmentBadge('LOST').cls).toBe('text-bg-danger');
    expect(shipmentBadge('EXCEPTION').cls).toBe('text-bg-warning');
    expect(shipmentBadge('toString')).toEqual({
      cls: 'text-bg-secondary',
      labelKey: 'theme.order.shipment.UNKNOWN',
    });
    expect(shipmentBadge(undefined).labelKey).toBe('theme.order.shipment.UNKNOWN');
  });

  test('tracking url is http(s) only', () => {
    expect(safeTrackingUrl('https://carrier.test/t/1')).toBe('https://carrier.test/t/1');
    expect(safeTrackingUrl('http://carrier.test/t/1')).toBe('http://carrier.test/t/1');
    for (const url of [
      'javascript:alert(1)',
      'data:text/html,x',
      'ftp://x.test',
      '/x',
      null,
      undefined,
      'https://a b',
    ])
      expect(safeTrackingUrl(url)).toBeNull();
  });

  test('address lines never include the identity number', () => {
    const lines = addressLines({
      firstName: 'Ada',
      lastName: 'Lovelace',
      company: 'ACME',
      line1: '1 Main St',
      line2: 'Apt 2',
      postalCode: '12345',
      city: 'Berlin',
      state: 'BE',
      country: 'DE',
      phone: '+491234',
      identityNumber: '11111111111',
    });
    expect(lines).toEqual([
      'Ada Lovelace',
      'ACME',
      '1 Main St',
      'Apt 2',
      '12345 Berlin, BE',
      '+491234',
    ]);
    expect(JSON.stringify(lines)).not.toContain('11111111111');
    expect(addressLines(null)).toEqual([]);
    expect(addressLines({})).toEqual([]);
  });

  test('company billing shows tax number and office', () => {
    expect(
      addressLines({ type: 'COMPANY', company: 'X', taxNumber: '123', taxOffice: 'Kadikoy' }),
    ).toEqual(['X', '123 (Kadikoy)']);
  });

  test('invoice link and blob decision', () => {
    expect(invoicePath(ID)).toBe(`/api/market/orders/${ID}/invoice`);
    expect(invoiceNeedsBlob('tok123')).toBe(true);
    expect(invoiceNeedsBlob(null)).toBe(false);
  });

  test('expiryLeft', () => {
    expect(expiryLeft({ expiresAt: 10_000 }, 4_000)).toBe(6_000);
    expect(expiryLeft({ expiresAt: 10_000 }, 20_000)).toBe(0);
    expect(expiryLeft({ expiresAt: 10_000 }, 0)).toBeNull();
    expect(expiryLeft({}, 5)).toBeNull();
    expect(expiryLeft(null, 5)).toBeNull();
  });
});

describe('load', () => {
  test('order id must be 20 alphanumerics', () => {
    expect(parseOrderId(ID)).toBe(ID);
    for (const bad of [
      '',
      'short',
      `${ID}x`,
      'AbCdEfGhIjKlMnOpQr-_',
      'AbCdEfGhIjKlMnOpQr S',
      '../etc/passwd/xxxxx',
      null,
      undefined,
      12345678901234567890,
    ])
      expect(parseOrderId(bad)).toBeNull();
  });

  test('return hint is one of success | cancel | pending', () => {
    for (const ok of ['success', 'cancel', 'pending']) expect(parseReturnHint(ok)).toBe(ok);
    for (const bad of ['', 'SUCCESS', 'paid', null, undefined, 'success '])
      expect(parseReturnHint(bad)).toBeNull();
  });

  test('a bad id or NOT_FOUND is a 404', () => {
    expect(resolveOrderLoad({ id: null, res: { ok: true, order: order() } })).toEqual({
      notFound: true,
    });
    expect(resolveOrderLoad({ id: ID, res: { ok: false, code: 'NOT_FOUND' } })).toEqual({
      notFound: true,
    });
  });

  test('ok => READY with the url token, hint, settings, title values and meta', () => {
    const res = resolveOrderLoad({
      id: ID,
      token: 'tok-abc',
      returnHint: 'success',
      res: { ok: true, order: order() },
      settings: { removeCents: true },
      features: { meta: true },
    });
    expect(res.data).toEqual({
      state: 'READY',
      id: ID,
      order: order(),
      urlToken: 'tok-abc',
      returnHint: 'success',
      settings: { removeCents: true },
    });
    expect(res.pageTitle).toEqual({
      title: 'plugins.pano-plugin-market.theme.order.title',
      titleValues: { number: 42 },
    });
    expect(res.meta).toEqual({ robots: 'noindex,nofollow', referrer: 'no-referrer' });
  });

  test('no meta without the page-meta feature; no number => the plain title', () => {
    const res = resolveOrderLoad({
      id: ID,
      res: { ok: true, order: order({ number: null, limited: true }) },
    });
    expect('meta' in res).toBe(false);
    expect(res.pageTitle).toEqual({ title: 'plugins.pano-plugin-market.theme.order.title-plain' });
    expect(res.data.settings).toEqual({});
    expect(res.data.urlToken).toBeNull();
  });

  test('other errors => ERROR with the code; a body without an order => NETWORK', () => {
    expect(
      resolveOrderLoad({ id: ID, res: { ok: false, code: 'STORE_DISABLED' } }).data,
    ).toMatchObject({ state: 'ERROR', code: 'STORE_DISABLED' });
    expect(resolveOrderLoad({ id: ID, res: { ok: false, code: 'NETWORK' } }).data.code).toBe(
      'NETWORK',
    );
    expect(resolveOrderLoad({ id: ID, res: { ok: true } }).data).toMatchObject({
      state: 'ERROR',
      code: 'NETWORK',
    });
    expect(resolveOrderLoad({ id: ID, res: undefined }).data.code).toBe('NETWORK');
  });

  test('an unusable ?token= is dropped (no header injection)', () => {
    for (const token of ['a b', 'a\r\nX: y', '', 'x'.repeat(257), null, 5])
      expect(
        resolveOrderLoad({ id: ID, token, res: { ok: true, order: order() } }).data.urlToken,
      ).toBeNull();
    expect(isUsableToken('abc_DEF-123.~')).toBe(true);
  });
});

describe('address bar clean-up', () => {
  test('token and return leave the URL, everything else stays', () => {
    expect(
      stripOrderParams(`https://shop.test/store/order/${ID}?token=SECRET&return=success`),
    ).toBe(`/store/order/${ID}`);
    expect(stripOrderParams(`/store/order/${ID}?token=SECRET&lang=tr#top`)).toBe(
      `/store/order/${ID}?lang=tr#top`,
    );
    expect(stripOrderParams(`/store/order/${ID}?return=cancel&a=1&token=x&b=2`)).toBe(
      `/store/order/${ID}?a=1&b=2`,
    );
    expect(stripOrderParams(`/store/order/${ID}`)).toBe(`/store/order/${ID}`);
  });

  test('the token never survives in the result', () => {
    expect(stripOrderParams(`/x?token=SECRET`)).not.toContain('SECRET');
    expect(stripOrderParams(`/x?a=1&token=SECRET&token=OTHER`)).not.toContain('SECRET');
  });

  test('hasOrderParams', () => {
    expect(hasOrderParams('/x?token=1')).toBe(true);
    expect(hasOrderParams('/x?return=success')).toBe(true);
    expect(hasOrderParams('/x?a=1')).toBe(false);
    expect(hasOrderParams('/x')).toBe(false);
  });
});

describe('tokenAfterRefetch (14 §11.2 step 2)', () => {
  test('the server answered and the view is still limited: the token is wrong, drop it', () => {
    expect(tokenAfterRefetch({ ok: true, limited: true })).toBe('DROP');
  });

  test('the server answered with the full view: keep it', () => {
    expect(tokenAfterRefetch({ ok: true, limited: false })).toBe('KEEP');
  });

  test('a failed fetch (NETWORK, 429, 5xx) proves nothing: keep it, even when the old view is limited', () => {
    expect(tokenAfterRefetch({ ok: false, limited: true })).toBe('KEEP');
    expect(tokenAfterRefetch({ ok: false, limited: false })).toBe('KEEP');
    expect(tokenAfterRefetch({})).toBe('KEEP');
    expect(tokenAfterRefetch()).toBe('KEEP');
  });
});
