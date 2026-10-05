import { describe, expect, test } from 'bun:test';
import { NODE } from '../../utils/permissions.js';
import {
  ACTION_DEFS,
  ACTION_FLAGS,
  deliveryActions,
  deliveryRetryable,
  disputeActions,
  isStaleError,
  itemActions,
  mailActions,
  orderActions,
  paymentActions,
  refundActions,
  shipmentActions,
  shipmentRowItems,
  showInvoicesCard,
  withAvailable,
} from './actions.js';

const admin = { admin: true, permissions: [] };
const holder = (...keys) => ({ admin: false, permissions: keys.map((k) => NODE[k]) });
const viewer = holder('OV');
const pay = holder('PAY');
const om = holder('OM');
const payOm = holder('PAY', 'OM');

const allTrue = () => Object.fromEntries(ACTION_FLAGS.map((f) => [f, true]));
const detailWith = (allowed) => ({ allowed });
const ids = (list) => list.map((a) => a.id);

describe('orderActions: flag AND node (13 §6.2)', () => {
  test('every flag has its own item and a node of PAY or OM', () => {
    expect(ACTION_FLAGS.sort()).toEqual(
      [
        'anonymize',
        'bankTransfer',
        'cancel',
        'createShipment',
        'dispute',
        'editShippingAddress',
        'markPaid',
        'refund',
        'resendMail',
        'review',
        'revoke',
        'rerunDelivery',
        'runChargebackActions',
      ].sort(),
    );
    for (const def of ACTION_DEFS) expect(['PAY', 'OM']).toContain(def.node);
  });

  test('the table of 13 §6.2: item -> flag -> node', () => {
    const expected = {
      markPaid: ['markPaid', 'PAY'],
      cancel: ['cancel', 'PAY'],
      markFailed: ['cancel', 'PAY'],
      review: ['review', 'PAY'],
      approveTransfer: ['bankTransfer', 'PAY'],
      rejectTransfer: ['bankTransfer', 'PAY'],
      refund: ['refund', 'PAY'],
      dispute: ['dispute', 'PAY'],
      rerunDelivery: ['rerunDelivery', 'OM'],
      revoke: ['revoke', 'OM'],
      editShippingAddress: ['editShippingAddress', 'OM'],
      runChargebackActions: ['runChargebackActions', 'PAY'],
      anonymize: ['anonymize', 'PAY'],
      createShipment: ['createShipment', 'OM'],
      resendMail: ['resendMail', 'OM'],
    };
    expect(Object.fromEntries(ACTION_DEFS.map((d) => [d.id, [d.flag, d.node]]))).toEqual(expected);
  });

  test('each flag alone shows exactly the items of that flag, for a user holding the node', () => {
    for (const flag of ACTION_FLAGS) {
      const detail = detailWith({ [flag]: true });
      const want = ACTION_DEFS.filter((d) => d.flag === flag).map((d) => d.id);
      expect(ids(orderActions(detail, admin))).toEqual(want);
      expect(ids(orderActions(detail, payOm))).toEqual(want);
    }
  });

  test('a flag without the node shows nothing, a node without the flag shows nothing', () => {
    for (const def of ACTION_DEFS) {
      const wrong = def.node === 'PAY' ? om : pay;
      expect(
        orderActions(detailWith({ [def.flag]: true }), wrong).filter((a) => a.id === def.id),
      ).toEqual([]);
      expect(orderActions(detailWith({ [def.flag]: false }), admin)).toEqual([]);
      expect(orderActions(detailWith({}), admin)).toEqual([]);
    }
  });

  test('a view-only user gets no dropdown whatever the flags are', () => {
    expect(orderActions(detailWith(allTrue()), viewer)).toEqual([]);
    expect(orderActions(detailWith(allTrue()), null)).toEqual([]);
  });

  test('the umbrella node and admins see everything the flags allow', () => {
    const umbrella = { admin: false, permissions: [NODE.ALL] };
    expect(orderActions(detailWith(allTrue()), umbrella)).toHaveLength(ACTION_DEFS.length);
    expect(orderActions(detailWith(allTrue()), admin)).toHaveLength(ACTION_DEFS.length);
  });

  test('missing or malformed allowed{} yields no items; truthy non-true values do not count', () => {
    expect(orderActions({}, admin)).toEqual([]);
    expect(orderActions(null, admin)).toEqual([]);
    expect(orderActions({ allowed: 'yes' }, admin)).toEqual([]);
    expect(orderActions(detailWith({ markPaid: 1, cancel: 'true' }), admin)).toEqual([]);
  });

  test('Mark As Failed and Cancel Order share allowed.cancel, Approve / Reject Transfer share allowed.bankTransfer', () => {
    expect(ids(orderActions(detailWith({ cancel: true }), pay))).toEqual(['cancel', 'markFailed']);
    expect(ids(orderActions(detailWith({ bankTransfer: true }), pay))).toEqual([
      'approveTransfer',
      'rejectTransfer',
    ]);
  });

  test('refund, shipping address and shipment items are external; withAvailable drops unwired ones', () => {
    const items = orderActions(detailWith(allTrue()), admin);
    expect(items.filter((a) => a.kind === 'external').map((a) => a.id)).toEqual([
      'refund',
      'editShippingAddress',
      'createShipment',
    ]);
    const kept = withAvailable(items, { refund: {} });
    expect(kept.some((a) => a.id === 'refund')).toBe(true);
    expect(kept.some((a) => a.id === 'createShipment' || a.id === 'editShippingAddress')).toBe(
      false,
    );
    expect(kept.filter((a) => a.kind !== 'external')).toHaveLength(items.length - 3);
    expect(withAvailable(items, {}).some((a) => a.kind === 'external')).toBe(false);
  });
});

describe('stale errors', () => {
  test('flag-stale codes refresh the page', () => {
    for (const code of [
      'INVALID_ORDER_TRANSITION',
      'INVALID_STATE',
      'NOT_FOUND',
      'DELIVERY_NOT_RETRYABLE',
      'ORDER_NOT_SHIPPABLE',
    ])
      expect(isStaleError(code)).toBe(true);
    for (const code of ['OUT_OF_STOCK', 'NETWORK_ERROR', 'NO_PERMISSION', undefined])
      expect(isStaleError(code)).toBe(false);
  });
});

describe('row actions', () => {
  test('items: OM with the flags; bundle parent has none', () => {
    const detail = detailWith({ rerunDelivery: true, revoke: true });
    expect(itemActions({ kind: 'PRODUCT' }, detail, om)).toEqual(['rerun', 'revoke']);
    expect(itemActions({ kind: 'PRODUCT' }, detailWith({ revoke: true }), om)).toEqual(['revoke']);
    expect(itemActions({ kind: 'PRODUCT' }, detail, pay)).toEqual([]);
    expect(itemActions({ kind: 'BUNDLE' }, detail, om)).toEqual([]);
    expect(itemActions({ kind: 'BUNDLE_CHILD' }, detail, admin)).toEqual(['rerun', 'revoke']);
  });

  test('payments: events always, query only for OM on open attempts', () => {
    for (const status of ['CREATED', 'PENDING', 'PROCESSING'])
      expect(paymentActions({ status }, om)).toEqual(['events', 'query']);
    for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'REVIEW'])
      expect(paymentActions({ status }, om)).toEqual(['events']);
    expect(paymentActions({ status: 'PENDING' }, pay)).toEqual(['events']);
  });

  test('refunds: PAY only; retry FAILED, cancel REQUESTED / PENDING', () => {
    expect(refundActions({ status: 'FAILED' }, pay)).toEqual(['retry']);
    expect(refundActions({ status: 'REQUESTED' }, pay)).toEqual(['cancel']);
    expect(refundActions({ status: 'PENDING' }, pay)).toEqual(['cancel']);
    expect(refundActions({ status: 'SUCCEEDED' }, pay)).toEqual([]);
    expect(refundActions({ status: 'CANCELLED' }, pay)).toEqual([]);
    expect(refundActions({ status: 'FAILED' }, om)).toEqual([]);
  });

  test('disputes: PAY only and only while OPEN', () => {
    expect(disputeActions({ status: 'OPEN' }, pay)).toEqual(['WON', 'LOST', 'CLOSED']);
    for (const status of ['INQUIRY', 'WON', 'LOST', 'CLOSED', 'NONE'])
      expect(disputeActions({ status }, pay)).toEqual([]);
    expect(disputeActions({ status: 'OPEN' }, om)).toEqual([]);
  });

  test('mails: retry for FAILED / SKIPPED with OM', () => {
    expect(mailActions({ status: 'FAILED' }, om)).toEqual(['retry']);
    expect(mailActions({ status: 'SKIPPED' }, om)).toEqual(['retry']);
    expect(mailActions({ status: 'SENT' }, om)).toEqual([]);
    expect(mailActions({ status: 'PENDING' }, om)).toEqual([]);
    expect(mailActions({ status: 'FAILED' }, pay)).toEqual([]);
  });

  test('deliveries: retry rules of 04 §7 and 13 §9.2', () => {
    const now = 1_700_000_000_000;
    const day = 86_400_000;
    expect(deliveryActions({ status: 'FAILED', lastErrorCode: 'COMMAND_ERROR' }, om, now)).toEqual([
      'retry',
    ]);
    expect(
      deliveryActions(
        { status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME', sentAt: now - day },
        om,
        now,
      ),
    ).toEqual(['retry']);
    for (const lastErrorCode of [
      'RENDER_ERROR',
      'NO_TARGET_SERVER',
      'SERVER_REMOVED',
      'INVALID_PLAYER',
    ])
      expect(deliveryActions({ status: 'FAILED', lastErrorCode }, om, now)).toEqual([]);
    expect(deliveryActions({ status: 'FAILED', sentAt: now - 31 * day }, om, now)).toEqual([]);
    expect(deliveryActions({ status: 'WAITING_SERVER' }, om, now)).toEqual(['retry', 'cancel']);
    expect(deliveryActions({ status: 'SENT' }, om, now)).toEqual(['offer-again', 'cancel']);
    expect(deliveryActions({ status: 'PENDING' }, om, now)).toEqual(['cancel']);
    expect(deliveryActions({ status: 'SCHEDULED' }, om, now)).toEqual(['cancel']);
    expect(deliveryActions({ status: 'QUEUED' }, om, now)).toEqual(['cancel']);
    for (const status of ['CONFIRMED', 'CANCELLED', 'SENDING', 'WAITING_PLAYER'])
      expect(deliveryActions({ status }, om, now)).toEqual([]);
    expect(deliveryActions({ status: 'FAILED' }, pay, now)).toEqual([]);
    expect(deliveryRetryable(null)).toBe(false);
  });

  test('shipments: OM; label kind, retry on CREATED with an error, cancel before pickup', () => {
    expect(shipmentActions({ status: 'CREATED', labelFile: 'a.pdf' }, om)).toEqual([
      'label',
      'track',
      'cancel',
    ]);
    expect(shipmentActions({ status: 'CREATED', lastErrorCode: 'X' }, om)).toEqual([
      'generic-label',
      'track',
      'retry',
      'cancel',
    ]);
    expect(shipmentActions({ status: 'LABEL_READY' }, om)).toEqual([
      'generic-label',
      'track',
      'cancel',
    ]);
    expect(shipmentActions({ status: 'IN_TRANSIT' }, om)).toEqual(['generic-label', 'track']);
    expect(shipmentActions({ status: 'CREATED' }, pay)).toEqual([]);
  });

  test('invoices card: hidden without invoicing and without documents', () => {
    expect(showInvoicesCard({ invoices: [] }, { invoiceEnabled: false }, om)).toBe(false);
    expect(showInvoicesCard({ invoices: [] }, null, om)).toBe(false);
    expect(showInvoicesCard({ invoices: [] }, { invoiceEnabled: true }, om)).toBe(true);
    expect(showInvoicesCard({ invoices: [{ id: 1 }] }, { invoiceEnabled: false }, om)).toBe(true);
    expect(showInvoicesCard({ invoices: [] }, { invoiceEnabled: true }, viewer)).toBe(false);
  });
});

describe('shipmentRowItems', () => {
  const shipment = { status: 'IN_TRANSIT', labelFile: 'a.pdf' };

  test('View leads when wired, for a view-only user too', () => {
    expect(shipmentRowItems(shipment, viewer, true)).toEqual(['view']);
    expect(shipmentRowItems(shipment, om, true)).toEqual([
      'view',
      ...shipmentActions(shipment, om),
    ]);
  });

  test('without a ShipmentModal the menu is the plain action list', () => {
    expect(shipmentRowItems(shipment, om)).toEqual(shipmentActions(shipment, om));
    expect(shipmentRowItems(shipment, viewer)).toEqual([]);
  });
});
