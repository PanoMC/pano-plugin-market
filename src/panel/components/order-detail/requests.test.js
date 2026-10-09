import { describe, expect, test } from 'bun:test';
import {
  DISPUTE_REASON_MAX,
  NOTE_MAX,
  RESEND_KINDS,
  STATUS_MODES,
  anonymizeRequest,
  chargebackActionsRequest,
  deliveryCancelRequest,
  deliveryRetryRequest,
  disputeRequest,
  disputeStatusRequest,
  exchangeRateRefreshRequest,
  exchangeRateRequest,
  invoiceRegenerateRequest,
  isEmail,
  mailRetryRequest,
  noteRequest,
  paymentQueryRequest,
  refundCancelRequest,
  refundRetryRequest,
  rerunGrantsAgain,
  rerunOutcome,
  rerunRequest,
  rerunSelectable,
  resendRequest,
  reviewOffersRefund,
  reviewRequest,
  revokeRequest,
  shipmentCancelRequest,
  shipmentLabelPath,
  shipmentRetryRequest,
  shipmentTrackRequest,
  statusRequest,
} from './requests.js';

const API = '';

describe('statusRequest (13 §6.2)', () => {
  test('mark paid / cancel / failed go to PUT status', () => {
    expect(statusRequest(5, 'markPaid', '').request).toEqual({
      method: 'PUT',
      path: `${API}/orders/5/status`,
      body: { status: 'COMPLETED' },
    });
    expect(statusRequest(5, 'cancel', ' why ').request.body).toEqual({
      status: 'CANCELLED',
      note: 'why',
    });
    expect(statusRequest(5, 'markFailed', undefined).request.body).toEqual({ status: 'FAILED' });
  });

  test('bank transfer decisions go to POST bank-transfer', () => {
    expect(statusRequest(5, 'approveTransfer', 'ok').request).toEqual({
      method: 'POST',
      path: `${API}/orders/5/bank-transfer`,
      body: { decision: 'APPROVE', note: 'ok' },
    });
    expect(statusRequest(5, 'rejectTransfer', '').request.body).toEqual({ decision: 'REJECT' });
  });

  test('CTA variants: only the destructive modes are danger', () => {
    expect(
      Object.fromEntries(Object.entries(STATUS_MODES).map(([k, v]) => [k, v.variant])),
    ).toEqual({
      markPaid: 'primary',
      cancel: 'danger',
      markFailed: 'danger',
      approveTransfer: 'primary',
      rejectTransfer: 'danger',
    });
  });

  test('a note over 255 characters is rejected, 255 passes; unknown mode throws', () => {
    expect(statusRequest(1, 'cancel', 'x'.repeat(NOTE_MAX + 1)).error).toEqual({ note: true });
    expect(statusRequest(1, 'cancel', 'x'.repeat(NOTE_MAX)).request).toBeDefined();
    expect(() => statusRequest(1, 'refund', '')).toThrow();
  });
});

describe('reviewRequest (13 §6.4)', () => {
  const paid = { id: 9, paidAmount: 20 };
  const unpaid = { id: 9, paidAmount: 0 };

  test('accept carries neither refund nor note when empty', () => {
    expect(reviewRequest(paid, { decision: 'ACCEPT', refund: true }).request).toEqual({
      method: 'POST',
      path: `${API}/orders/9/review`,
      body: { decision: 'ACCEPT' },
    });
  });

  test('reject of a paid order sends the refund switch (default on)', () => {
    expect(reviewRequest(paid, { decision: 'REJECT' }).request.body).toEqual({
      decision: 'REJECT',
      refund: true,
    });
    expect(
      reviewRequest(paid, { decision: 'REJECT', refund: false, note: 'fraud' }).request.body,
    ).toEqual({
      decision: 'REJECT',
      refund: false,
      note: 'fraud',
    });
  });

  test('reject of an unpaid order has no refund key; the switch exists only then', () => {
    expect(reviewRequest(unpaid, { decision: 'REJECT', refund: true }).request.body).toEqual({
      decision: 'REJECT',
    });
    expect(reviewOffersRefund(paid, 'REJECT')).toBe(true);
    expect(reviewOffersRefund(paid, 'ACCEPT')).toBe(false);
    expect(reviewOffersRefund(unpaid, 'REJECT')).toBe(false);
  });

  test('force only on accept', () => {
    expect(reviewRequest(paid, { decision: 'ACCEPT', force: true }).request.body).toEqual({
      decision: 'ACCEPT',
      force: true,
    });
    expect(
      reviewRequest(paid, { decision: 'REJECT', force: true }).request.body.force,
    ).toBeUndefined();
  });

  test('invalid decision and long note are rejected', () => {
    expect(reviewRequest(paid, { decision: 'MAYBE' }).error).toEqual({ decision: true });
    expect(reviewRequest(paid, { decision: 'ACCEPT', note: 'x'.repeat(256) }).error).toEqual({
      note: true,
    });
  });
});

describe('disputeRequest', () => {
  test('amount and reason are optional', () => {
    expect(disputeRequest(3, { amount: null, reason: '' }).request).toEqual({
      method: 'POST',
      path: `${API}/orders/3/disputes`,
      body: {},
    });
    expect(disputeRequest(3, { amount: 12.5, reason: ' chargeback ' }).request.body).toEqual({
      amount: 12.5,
      reason: 'chargeback',
    });
  });

  test('amount must be a number above zero; NaN (MoneyInput malformed) is rejected', () => {
    expect(disputeRequest(3, { amount: 0, reason: '' }).error).toEqual({ amount: true });
    expect(disputeRequest(3, { amount: NaN, reason: '' }).error).toEqual({ amount: true });
    expect(disputeRequest(3, { amount: -1, reason: '' }).error).toEqual({ amount: true });
  });

  test('reason is limited to 255', () => {
    expect(
      disputeRequest(3, { amount: 1, reason: 'x'.repeat(DISPUTE_REASON_MAX + 1) }).error,
    ).toEqual({ reason: true });
    expect(disputeRequest(3, { amount: NaN, reason: 'x'.repeat(300) }).error).toEqual({
      amount: true,
      reason: true,
    });
  });

  test('resolutions', () => {
    expect(disputeStatusRequest(4, 'WON')).toEqual({
      method: 'PUT',
      path: `${API}/disputes/4`,
      body: { status: 'WON' },
    });
    expect(() => disputeStatusRequest(4, 'OPEN')).toThrow();
  });
});

describe('rerunRequest (13 §9.3)', () => {
  test('exactly one id set is sent, phase always', () => {
    expect(rerunRequest(8, { scope: 'all' }).request).toEqual({
      method: 'POST',
      path: `${API}/orders/8/deliveries/rerun`,
      body: { phase: 'GRANT', all: true },
    });
    expect(
      rerunRequest(8, { scope: 'items', itemIds: [1, 2], deliveryIds: [9], phase: 'RENEW' }).request
        .body,
    ).toEqual({
      phase: 'RENEW',
      orderItemIds: [1, 2],
    });
    expect(
      rerunRequest(8, { scope: 'deliveries', itemIds: [1], deliveryIds: [5], phase: 'REVOKE' })
        .request.body,
    ).toEqual({
      phase: 'REVOKE',
      deliveryIds: [5],
    });
  });

  test('empty selection, unknown scope and unknown phase are rejected', () => {
    expect(rerunRequest(8, { scope: 'items', itemIds: [] }).error).toEqual({ selection: true });
    expect(rerunRequest(8, { scope: 'deliveries', deliveryIds: [] }).error).toEqual({
      selection: true,
    });
    expect(rerunRequest(8, { scope: 'everything' }).error).toEqual({ selection: true });
    expect(rerunRequest(8, { scope: 'all', phase: 'DESTROY' }).error).toEqual({ selection: true });
  });

  test('the id arrays are copied', () => {
    const ids = [1];
    const body = rerunRequest(8, { scope: 'items', itemIds: ids }).request.body;
    ids.push(2);
    expect(body.orderItemIds).toEqual([1]);
  });

  test('selectable: FAILED, CANCELLED, SENT, CONFIRMED; effect rows need PAY', () => {
    expect(rerunSelectable({ status: 'FAILED', lastErrorCode: 'COMMAND_ERROR' }, false)).toBe(true);
    expect(rerunSelectable({ status: 'CANCELLED' }, false)).toBe(true);
    expect(rerunSelectable({ status: 'SENT' }, false)).toBe(true);
    expect(rerunSelectable({ status: 'CONFIRMED' }, false)).toBe(false);
    expect(rerunSelectable({ status: 'CONFIRMED' }, true)).toBe(true);
    expect(rerunSelectable({ status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME' }, false)).toBe(
      false,
    );
    expect(rerunSelectable({ status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME' }, true)).toBe(
      true,
    );
    for (const status of ['PENDING', 'SENDING', 'SCHEDULED', 'WAITING_SERVER', 'QUEUED'])
      expect(rerunSelectable({ status }, true)).toBe(false);
  });

  test('grants-again warning follows the scope', () => {
    const deliveries = [
      { id: 1, orderItemId: 10, status: 'CONFIRMED' },
      { id: 2, orderItemId: 11, status: 'FAILED', lastErrorCode: 'COMMAND_ERROR' },
      { id: 3, orderItemId: 12, status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME' },
    ];
    expect(rerunGrantsAgain({ scope: 'all' }, deliveries)).toBe(true);
    expect(rerunGrantsAgain({ scope: 'all' }, [deliveries[1]])).toBe(false);
    expect(rerunGrantsAgain({ scope: 'items', itemIds: [11] }, deliveries)).toBe(false);
    expect(rerunGrantsAgain({ scope: 'items', itemIds: [10, 11] }, deliveries)).toBe(true);
    expect(rerunGrantsAgain({ scope: 'deliveries', deliveryIds: [2] }, deliveries)).toBe(false);
    expect(rerunGrantsAgain({ scope: 'deliveries', deliveryIds: [3] }, deliveries)).toBe(true);
    expect(rerunGrantsAgain({ scope: 'deliveries', deliveryIds: [] }, deliveries)).toBe(false);
    expect(rerunGrantsAgain({ scope: 'all' }, undefined)).toBe(false);
  });

  test('outcome: none is info, skipped has its own text, plain success carries the count', () => {
    expect(rerunOutcome({ created: 0, skipped: 3 })).toEqual({
      key: 'modals.rerun.toast-none',
      values: {},
      variant: 'info',
    });
    expect(rerunOutcome({})).toMatchObject({ key: 'modals.rerun.toast-none' });
    expect(rerunOutcome({ created: 2, skipped: 1 })).toEqual({
      key: 'modals.rerun.toast-skipped',
      values: { count: 2, skipped: 1 },
      variant: 'success',
    });
    expect(rerunOutcome({ created: 4, skipped: 0 })).toEqual({
      key: 'modals.rerun.toast',
      values: { count: 4 },
      variant: 'success',
    });
  });
});

describe('resendRequest (13 §6.2)', () => {
  test('the six kinds of the spec', () => {
    expect(RESEND_KINDS).toEqual([
      'ORDER_CONFIRMATION',
      'GIFT_RECEIVED',
      'BANK_TRANSFER_INSTRUCTIONS',
      'ORDER_REFUNDED',
      'SHIPMENT_SHIPPED',
      'SHIPMENT_DELIVERED',
    ]);
  });

  test('recipient is optional and trimmed; order mails carry no refId', () => {
    expect(resendRequest(2, { kind: 'ORDER_CONFIRMATION' }).request).toEqual({
      method: 'POST',
      path: `${API}/orders/2/mails/resend`,
      body: { kind: 'ORDER_CONFIRMATION' },
    });
    expect(resendRequest(2, { kind: 'GIFT_RECEIVED', recipient: ' a@b.co ' }).request.body).toEqual(
      { kind: 'GIFT_RECEIVED', recipient: 'a@b.co' },
    );
  });

  test('shipment mails need a shipment id', () => {
    expect(resendRequest(2, { kind: 'SHIPMENT_SHIPPED' }).error).toEqual({ shipment: true });
    expect(resendRequest(2, { kind: 'SHIPMENT_DELIVERED', shipmentId: 0 }).error).toEqual({
      shipment: true,
    });
    expect(resendRequest(2, { kind: 'SHIPMENT_DELIVERED', shipmentId: 6 }).request.body).toEqual({
      kind: 'SHIPMENT_DELIVERED',
      refId: 6,
    });
  });

  test('bad kind and bad e-mail are rejected', () => {
    expect(resendRequest(2, { kind: 'ORDER_RECEIVED' }).error).toEqual({ kind: true });
    expect(resendRequest(2, { kind: 'ORDER_REFUNDED', recipient: 'not-an-email' }).error).toEqual({
      recipient: true,
    });
    expect(isEmail('a@b.co')).toBe(true);
    expect(isEmail('a b@c.co')).toBe(false);
    expect(isEmail('a@b')).toBe(false);
    expect(isEmail(`${'a'.repeat(251)}@b.co`)).toBe(false);
  });
});

describe('plain requests', () => {
  test('paths and methods', () => {
    expect(revokeRequest(1)).toEqual({ method: 'POST', path: `${API}/orders/1/revoke`, body: {} });
    expect(revokeRequest(1, [])).toEqual({
      method: 'POST',
      path: `${API}/orders/1/revoke`,
      body: {},
    });
    expect(revokeRequest(1, [4, 5]).body).toEqual({ orderItemIds: [4, 5] });
    const table = [
      [chargebackActionsRequest(1), 'POST', '/orders/1/chargeback-actions'],
      [anonymizeRequest(1), 'POST', '/orders/1/anonymize'],
      [invoiceRegenerateRequest(1), 'POST', '/orders/1/invoice/regenerate'],
      [refundRetryRequest(2), 'POST', '/refunds/2/retry'],
      [refundCancelRequest(2), 'POST', '/refunds/2/cancel'],
      [deliveryRetryRequest(3), 'POST', '/deliveries/3/retry'],
      [deliveryCancelRequest(3), 'POST', '/deliveries/3/cancel'],
      [mailRetryRequest(4), 'POST', '/mails/4/retry'],
      [paymentQueryRequest(5), 'POST', '/payments/5/query'],
      [shipmentTrackRequest(6), 'POST', '/shipments/6/track'],
      [shipmentRetryRequest(6), 'POST', '/shipments/6/retry'],
      [shipmentCancelRequest(6), 'POST', '/shipments/6/cancel'],
      [exchangeRateRefreshRequest(1), 'POST', '/orders/1/exchange-rate/refresh'],
      [exchangeRateRequest(1, 31.5), 'PUT', '/orders/1/exchange-rate'],
    ];
    for (const [request, method, path] of table)
      expect(request).toMatchObject({ method, path: API + path });
    expect(exchangeRateRequest(1, 31.5).body).toEqual({ exchangeRate: 31.5 });
    expect(chargebackActionsRequest(1).body).toEqual({});
  });

  test('note: up to 2000 characters, kept verbatim (an empty note clears it)', () => {
    expect(noteRequest(1, 'hello').request).toEqual({
      method: 'PUT',
      path: `${API}/orders/1/note`,
      body: { note: 'hello' },
    });
    expect(noteRequest(1, '').request.body).toEqual({ note: '' });
    expect(noteRequest(1, 'x'.repeat(2000)).request).toBeDefined();
    expect(noteRequest(1, 'x'.repeat(2001)).error).toEqual({ note: true });
  });

  test('label links', () => {
    expect(shipmentLabelPath('/p', 3, false)).toBe(
      '/api/plugins/pano-plugin-market/panel/shipments/3/label',
    );
    expect(shipmentLabelPath('', 3, true)).toBe(
      '/api/plugins/pano-plugin-market/panel/shipments/3/label?generic=true',
    );
  });
});
