import { describe, expect, test } from 'bun:test';
import {
  AUTO_REFRESH_MAX,
  ORDER_EVENT_TYPES,
  addressLines,
  asText,
  autoRefreshNeeded,
  deliveryTookEffect,
  invoiceUrl,
  isBundleChild,
  isExternalPricing,
  isKnownActor,
  isKnownEventType,
  itemDeliveryState,
  mayHaveRun,
  parseExchangeRate,
  parseOrderId,
  remainingCollected,
  revokePending,
  shouldAutoRefresh,
  showListPrice,
  timelineOf,
  totalsRows,
} from './model.js';

const keys = (rows) => rows.map((r) => r.key);
const base = {
  subtotal: 100,
  discountTotal: 0,
  couponDiscount: 0,
  creatorDiscount: 0,
  upgradeDiscount: 0,
  shippingTotal: 0,
  paymentFee: 0,
  vatTotal: 0,
  totalPrice: 100,
  creditAmount: 0,
  creditValue: 0,
  gatewayAmount: 100,
  paidAmount: 100,
  refundedTotal: 0,
  refundedGatewayAmount: 0,
  refundedCreditAmount: 0,
};

describe('parseOrderId', () => {
  test('digits above zero only', () => {
    expect(parseOrderId('42')).toBe(42);
    expect(parseOrderId(' 7 ')).toBe(7);
    for (const bad of [
      '0',
      '-1',
      '1.5',
      'abc',
      '',
      null,
      undefined,
      '12abc',
      '99999999999999999999',
    ])
      expect(parseOrderId(bad)).toBeNull();
  });
});

describe('totalsRows (13 §6.1)', () => {
  test('zero rows are omitted except subtotal and total', () => {
    expect(keys(totalsRows(base))).toEqual(['subtotal', 'total', 'paid-gateway']);
    expect(
      keys(totalsRows({ ...base, subtotal: 0, totalPrice: 0, gatewayAmount: 0, paidAmount: 0 })),
    ).toEqual(['subtotal', 'total']);
  });

  test('every row in the documented order, with notes and signs', () => {
    const order = {
      ...base,
      subtotal: 200,
      discountTotal: 10,
      couponDiscount: 5,
      couponCode: 'WELCOME',
      creatorDiscount: 3,
      creatorCode: 'STEVE',
      upgradeDiscount: 2,
      shippingTotal: 15,
      shippingMethodName: 'Courier',
      paymentFee: 1.5,
      vatTotal: 30,
      pricesIncludeVat: true,
      totalPrice: 190,
      creditAmount: 500,
      creditValue: 50,
      gatewayAmount: 140,
      paidAmount: 145,
      refundedTotal: 20,
      refundedGatewayAmount: 15,
      refundedCreditAmount: 50,
      displayCurrency: 'USD',
      displayRate: 0.031,
    };
    const rows = totalsRows(order);
    expect(keys(rows)).toEqual([
      'subtotal',
      'discounts',
      'coupon',
      'creator-code',
      'upgrade',
      'shipping',
      'payment-fee',
      'vat',
      'total',
      'paid-credits',
      'paid-gateway',
      'collected',
      'refunded',
      'display',
    ]);
    const by = Object.fromEntries(rows.map((r) => [r.key, r]));
    expect(
      by.discounts.negative &&
        by.coupon.negative &&
        by['creator-code'].negative &&
        by.upgrade.negative,
    ).toBe(true);
    expect(by.shipping.negative).toBeUndefined();
    expect(by.coupon.note).toBe('WELCOME');
    expect(by['creator-code'].note).toBe('STEVE');
    expect(by.shipping.note).toBe('Courier');
    expect(by.vat.included).toBe(true);
    expect(by.total.strong).toBe(true);
    expect(by['paid-credits']).toMatchObject({ kind: 'credits', amount: 500, value: 50 });
    expect(by.collected.amount).toBe(145);
    expect(by.refunded).toMatchObject({ negative: true, amount: 20, gateway: 15, credits: 50 });
    expect(by.display).toMatchObject({ kind: 'text', currency: 'USD', rate: 0.031 });
  });

  test('collected only differs from gateway amount; a free order has no collected row', () => {
    expect(keys(totalsRows({ ...base, paidAmount: 100 }))).not.toContain('collected');
    expect(keys(totalsRows({ ...base, paidAmount: 100.004 }))).not.toContain('collected');
    expect(keys(totalsRows({ ...base, paidAmount: 105 }))).toContain('collected');
    expect(
      keys(totalsRows({ ...base, paidAmount: 0, gatewayAmount: 0, totalPrice: 0 })),
    ).not.toContain('collected');
  });

  test('nulls and a missing order do not throw', () => {
    expect(totalsRows(null)).toEqual([]);
    expect(keys(totalsRows({}))).toEqual(['subtotal', 'total']);
    expect(totalsRows({ subtotal: '12.5', totalPrice: '12.5' })[0].amount).toBe(12.5);
  });

  test('external pricing = anything but MARKET', () => {
    expect(isExternalPricing({ pricingMode: 'MARKET' })).toBe(false);
    expect(isExternalPricing({ pricingMode: 'EXTERNAL' })).toBe(true);
    expect(isExternalPricing({ pricingMode: 'EXTERNAL_TAX' })).toBe(true);
    expect(isExternalPricing({})).toBe(false);
  });
});

describe('parseExchangeRate', () => {
  test('a number above zero, comma or dot', () => {
    expect(parseExchangeRate('32,5')).toBe(32.5);
    expect(parseExchangeRate('0.031')).toBe(0.031);
    for (const bad of ['0', '0.0', '-1', '', 'abc', '1,2,3', '1e5', null])
      expect(parseExchangeRate(bad)).toBeNull();
  });
});

describe('items', () => {
  test('list price is struck only when it differs and exists; bundle children carry none', () => {
    expect(showListPrice({ listUnitPrice: 12, unitPrice: 10 })).toBe(true);
    expect(showListPrice({ listUnitPrice: 10, unitPrice: 10 })).toBe(false);
    expect(showListPrice({ listUnitPrice: 0, unitPrice: 0 })).toBe(false);
    expect(showListPrice({ kind: 'BUNDLE_CHILD', listUnitPrice: 5, unitPrice: 0 })).toBe(false);
    expect(isBundleChild({ kind: 'BUNDLE_CHILD' })).toBe(true);
    expect(isBundleChild({ kind: 'BUNDLE' })).toBe(false);
  });

  test('delivery state per item: none / failed / fulfilled / pending', () => {
    const d = (orderItemId, status) => ({ orderItemId, status });
    expect(itemDeliveryState(1, [])).toBe('none');
    expect(itemDeliveryState(1, [d(2, 'FAILED')])).toBe('none');
    expect(itemDeliveryState(1, [d(1, 'CONFIRMED'), d(1, 'SENT')])).toBe('fulfilled');
    expect(itemDeliveryState(1, [d(1, 'CONFIRMED'), d(1, 'FAILED')])).toBe('failed');
    expect(itemDeliveryState(1, [d(1, 'CONFIRMED'), d(1, 'PENDING')])).toBe('pending');
    expect(itemDeliveryState(1, [d(1, 'CANCELLED')])).toBe('pending');
    expect(itemDeliveryState(1, undefined)).toBe('none');
  });

  test('took effect / may have run', () => {
    expect(deliveryTookEffect({ status: 'CONFIRMED' })).toBe(true);
    expect(deliveryTookEffect({ status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME' })).toBe(true);
    expect(deliveryTookEffect({ status: 'FAILED', lastErrorCode: 'COMMAND_ERROR' })).toBe(false);
    expect(deliveryTookEffect({ status: 'SENT' })).toBe(false);
    expect(mayHaveRun({ status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME' })).toBe(true);
    expect(mayHaveRun({ status: 'CONFIRMED', lastErrorCode: 'UNKNOWN_OUTCOME' })).toBe(false);
  });

  test('revoke pending needs PARTIAL fulfillment and a count', () => {
    expect(revokePending({ order: { fulfillmentStatus: 'PARTIAL' }, revokePending: 2 })).toBe(true);
    expect(revokePending({ order: { fulfillmentStatus: 'PARTIAL' }, revokePending: 0 })).toBe(
      false,
    );
    expect(revokePending({ order: { fulfillmentStatus: 'FULFILLED' }, revokePending: 2 })).toBe(
      false,
    );
  });
});

describe('auto refresh (13 §6)', () => {
  const quiet = {
    order: { status: 'COMPLETED' },
    payments: [{ status: 'SUCCEEDED' }],
    refunds: [{ status: 'SUCCEEDED' }],
    deliveries: [{ status: 'CONFIRMED' }],
  };

  test('quiet order does not poll', () => {
    expect(autoRefreshNeeded(quiet)).toBe(false);
    expect(autoRefreshNeeded(null)).toBe(false);
    expect(autoRefreshNeeded({})).toBe(false);
  });

  test('each documented condition starts polling', () => {
    for (const status of ['PENDING', 'REVIEW'])
      expect(autoRefreshNeeded({ ...quiet, order: { status } })).toBe(true);
    for (const status of ['CREATED', 'PENDING', 'PROCESSING'])
      expect(autoRefreshNeeded({ ...quiet, payments: [{ status }] })).toBe(true);
    for (const status of ['REQUESTED', 'PENDING'])
      expect(autoRefreshNeeded({ ...quiet, refunds: [{ status }] })).toBe(true);
    for (const status of ['PENDING', 'SENDING', 'SENT', 'QUEUED'])
      expect(autoRefreshNeeded({ ...quiet, deliveries: [{ status }] })).toBe(true);
  });

  test('terminal or waiting rows do not', () => {
    expect(
      autoRefreshNeeded({
        ...quiet,
        deliveries: [{ status: 'WAITING_SERVER' }, { status: 'FAILED' }, { status: 'SCHEDULED' }],
      }),
    ).toBe(false);
    expect(
      autoRefreshNeeded({ ...quiet, payments: [{ status: 'FAILED' }, { status: 'EXPIRED' }] }),
    ).toBe(false);
  });

  test('a tick needs visibility, no modal and a run below the cap', () => {
    const ok = { needed: true, visible: true, modalOpen: false, runs: 0 };
    expect(shouldAutoRefresh(ok)).toBe(true);
    expect(shouldAutoRefresh({ ...ok, needed: false })).toBe(false);
    expect(shouldAutoRefresh({ ...ok, visible: false })).toBe(false);
    expect(shouldAutoRefresh({ ...ok, modalOpen: true })).toBe(false);
    expect(shouldAutoRefresh({ ...ok, runs: AUTO_REFRESH_MAX - 1 })).toBe(true);
    expect(shouldAutoRefresh({ ...ok, runs: AUTO_REFRESH_MAX })).toBe(false);
    expect(AUTO_REFRESH_MAX).toBe(40);
  });
});

describe('timeline', () => {
  test('newest first, ties by id, input untouched', () => {
    const events = [
      { id: 1, createdAt: 100 },
      { id: 3, createdAt: 300 },
      { id: 2, createdAt: 300 },
    ];
    expect(timelineOf(events).map((e) => e.id)).toEqual([3, 2, 1]);
    expect(events.map((e) => e.id)).toEqual([1, 3, 2]);
    expect(timelineOf(undefined)).toEqual([]);
  });

  test('event types of 01 §5.3 are known, anything else is shown raw', () => {
    expect(ORDER_EVENT_TYPES).toHaveLength(33);
    expect(isKnownEventType('REFUND_SUCCEEDED')).toBe(true);
    expect(isKnownEventType('SOMETHING_NEW')).toBe(false);
    expect(isKnownActor('GATEWAY')).toBe(true);
    expect(isKnownActor('ROBOT')).toBe(false);
  });
});

describe('addresses and invoices', () => {
  const address = {
    firstName: 'Ada',
    lastName: 'Lovelace',
    company: 'Analytical',
    line1: '1 St',
    line2: '',
    neighborhood: 'N',
    district: 'D',
    city: 'London',
    state: 'LND',
    postalCode: 'E1',
    country: 'GB',
    phone: '+44',
    type: 'COMPANY',
    taxOffice: 'TO',
    taxNumber: '123',
    identityNumber: '',
    extraKey: 'ignored',
  };

  test('lines in the documented order, empty parts skipped', () => {
    expect(addressLines(address).lines).toEqual([
      'Ada Lovelace',
      'Analytical',
      '1 St',
      'N',
      'D',
      'London',
      'LND',
      'E1',
      'GB',
      '+44',
    ]);
    expect(addressLines({ lastName: 'Only' }).lines).toEqual(['Only']);
    expect(addressLines(null)).toEqual({ lines: [], extra: [] });
    expect(addressLines(address).extra).toEqual([]);
  });

  test('billing adds type, tax office, tax number and identity number when present', () => {
    expect(addressLines(address, { billing: true }).extra).toEqual([
      { key: 'type', value: 'COMPANY' },
      { key: 'taxOffice', value: 'TO' },
      { key: 'taxNumber', value: '123' },
    ]);
  });

  test('invoice url carries the type and the refund id', () => {
    expect(invoiceUrl('/panel', 7, { type: 'INVOICE' })).toBe(
      '/api/plugins/pano-plugin-market/panel/orders/7/invoice?type=INVOICE',
    );
    expect(invoiceUrl('', 7, { type: 'CREDIT_NOTE', refundId: 3 })).toBe(
      '/api/plugins/pano-plugin-market/panel/orders/7/invoice?type=CREDIT_NOTE&refundId=3',
    );
  });
});

describe('small helpers', () => {
  test('remaining collected never goes negative and is rounded', () => {
    expect(remainingCollected({ paidAmount: 100, refundedGatewayAmount: 30.1 })).toBe(69.9);
    expect(remainingCollected({ paidAmount: 10, refundedGatewayAmount: 30 })).toBe(0);
    expect(remainingCollected(null)).toBe(0);
  });

  test('asText', () => {
    expect(asText(null)).toBe('');
    expect(asText('raw')).toBe('raw');
    expect(asText({ a: 1 })).toBe('{\n  "a": 1\n}');
  });
});

import { fieldEntries, fieldLabel, itemRefunded, itemTargetServer } from './model.js';

describe('item details', () => {
  test('field labels come from the snapshot when it has them, else the key', () => {
    expect(fieldLabel({ snapshot: { fieldLabels: { nick: 'Nickname' } } }, 'nick')).toBe(
      'Nickname',
    );
    expect(
      fieldLabel({ snapshot: { fields: [{ fieldKey: 'nick', name: 'In-Game Name' }] } }, 'nick'),
    ).toBe('In-Game Name');
    expect(fieldLabel({ snapshot: {} }, 'nick')).toBe('nick');
    expect(fieldLabel(null, 'nick')).toBe('nick');
  });

  test('field entries skip empty values and print objects as text', () => {
    const item = { fieldValues: { a: 'x', b: '', c: null, d: 5, e: { k: 1 } } };
    expect(fieldEntries(item).map((e) => [e.key, e.value])).toEqual([
      ['a', 'x'],
      ['d', '5'],
      ['e', '{\n  "k": 1\n}'],
    ]);
    expect(fieldEntries({})).toEqual([]);
    expect(fieldEntries({ fieldValues: 'x' })).toEqual([]);
  });

  test('target server: own name, delivery name, id, nothing', () => {
    expect(itemTargetServer({ id: 1, targetServerName: 'Lobby' }, [])).toBe('Lobby');
    expect(
      itemTargetServer({ id: 1, targetServerId: 4 }, [{ orderItemId: 1, serverName: 'Survival' }]),
    ).toBe('Survival');
    expect(
      itemTargetServer({ id: 1, targetServerId: 4 }, [{ orderItemId: 2, serverName: 'Other' }]),
    ).toBe('#4');
    expect(itemTargetServer({ id: 1 }, undefined)).toBe('');
  });

  test('refunded quantity', () => {
    expect(itemRefunded({ refundedQuantity: 1 })).toBe(true);
    expect(itemRefunded({ refundedQuantity: 0 })).toBe(false);
  });
});
