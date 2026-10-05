// Request builders of the order detail page (13 §6.2, §6.4, §9.3, §6.1). Pure: no Svelte, no SDK
// import. Each returns `{ method, path, body }` (path relative to the market API root) or, for forms,
// `{ error: { field: true } }` / `{ request }`.
import { marketPath } from '../../utils/api.js';

export const NOTE_MAX = 255;
export const ORDER_NOTE_MAX = 2000;
export const DISPUTE_REASON_MAX = 255;

const req = (method, path, body = {}) => ({ method, path: marketPath(path), body });

const clean = (text) => String(text ?? '').trim();
const optional = (body, key, value) => {
  const v = clean(value);
  if (v !== '') body[key] = v;
  return body;
};

// ---- status / bank transfer -------------------------------------------------------------------

/** mode -> request kind of the OrderStatusModal (13 §6.2). */
export const STATUS_MODES = {
  markPaid: { status: 'COMPLETED', variant: 'primary' },
  cancel: { status: 'CANCELLED', variant: 'danger' },
  markFailed: { status: 'FAILED', variant: 'danger' },
  approveTransfer: { decision: 'APPROVE', variant: 'primary' },
  rejectTransfer: { decision: 'REJECT', variant: 'danger' },
};

/** `{ request }` or `{ error: { note } }` (note over 255 characters). */
export function statusRequest(orderId, mode, note) {
  const def = STATUS_MODES[mode];
  if (!def) throw new Error(`unknown status mode ${mode}`);
  if (clean(note).length > NOTE_MAX) return { error: { note: true } };
  const body = def.status ? { status: def.status } : { decision: def.decision };
  optional(body, 'note', note);
  return {
    request: def.status
      ? req('PUT', `/orders/${orderId}/status`, body)
      : req('POST', `/orders/${orderId}/bank-transfer`, body),
  };
}

// ---- review -----------------------------------------------------------------------------------

/** The refund switch exists for a REJECT of an order that received money. */
export const reviewOffersRefund = (order, decision) =>
  decision === 'REJECT' && Number(order?.paidAmount) > 0;

/** Codes after which an ACCEPT may be forced (13 §6.4 / 04 §7 `force`). */
export const FORCEABLE_REVIEW_ERRORS = new Set([
  'OUT_OF_STOCK',
  'PURCHASE_LIMIT_REACHED',
  'INVALID_COUPON',
]);

export function reviewRequest(order, { decision, refund = true, note = '', force = false }) {
  if (decision !== 'ACCEPT' && decision !== 'REJECT') return { error: { decision: true } };
  if (clean(note).length > NOTE_MAX) return { error: { note: true } };
  const body = { decision };
  if (reviewOffersRefund(order, decision)) body.refund = refund === true;
  if (decision === 'ACCEPT' && force === true) body.force = true;
  optional(body, 'note', note);
  return { request: req('POST', `/orders/${order.id}/review`, body) };
}

// ---- dispute ----------------------------------------------------------------------------------

/** amount: Number | null | NaN (MoneyInput), reason: text. Empty amount = server default. */
export function disputeRequest(orderId, { amount, reason }) {
  const error = {};
  if (amount !== null && amount !== undefined && !(Number.isFinite(amount) && amount > 0))
    error.amount = true;
  if (clean(reason).length > DISPUTE_REASON_MAX) error.reason = true;
  if (Object.keys(error).length > 0) return { error };
  const body = {};
  if (amount !== null && amount !== undefined) body.amount = amount;
  optional(body, 'reason', reason);
  return { request: req('POST', `/orders/${orderId}/disputes`, body) };
}

export const DISPUTE_RESOLUTIONS = ['WON', 'LOST', 'CLOSED'];

export function disputeStatusRequest(disputeId, status) {
  if (!DISPUTE_RESOLUTIONS.includes(status)) throw new Error(`unknown dispute status ${status}`);
  return req('PUT', `/disputes/${disputeId}`, { status });
}

// ---- re-run -----------------------------------------------------------------------------------

export const RERUN_PHASES = ['GRANT', 'RENEW', 'EXPIRE', 'REVOKE'];
export const RERUN_SCOPES = ['all', 'items', 'deliveries'];
export const RERUN_DELIVERY_STATUSES = ['FAILED', 'CANCELLED', 'SENT', 'CONFIRMED'];

/**
 * Exactly one of the three id sets is sent. `{ error: { selection } }` for an empty selection or an
 * unknown scope / phase.
 */
export function rerunRequest(orderId, { scope, itemIds = [], deliveryIds = [], phase = 'GRANT' }) {
  if (!RERUN_PHASES.includes(phase) || !RERUN_SCOPES.includes(scope))
    return { error: { selection: true } };
  const body = { phase };
  if (scope === 'all') body.all = true;
  else if (scope === 'items') {
    if (itemIds.length === 0) return { error: { selection: true } };
    body.orderItemIds = [...itemIds];
  } else {
    if (deliveryIds.length === 0) return { error: { selection: true } };
    body.deliveryIds = [...deliveryIds];
  }
  return { request: req('POST', `/orders/${orderId}/deliveries/rerun`, body) };
}

/** A delivery is selectable when it is in a re-runnable status; rows that took effect need PAY too. */
export function rerunSelectable(delivery, canPay) {
  if (!RERUN_DELIVERY_STATUSES.includes(delivery?.status)) return false;
  const tookEffect =
    delivery.status === 'CONFIRMED' ||
    (delivery.status === 'FAILED' && delivery.lastErrorCode === 'UNKNOWN_OUTCOME');
  return !tookEffect || canPay === true;
}

/**
 * True when the selection would grant again: any selected delivery (or, for items / all, any delivery
 * of those items) already took effect.
 */
export function rerunGrantsAgain({ scope, itemIds = [], deliveryIds = [] }, deliveries) {
  const took = (d) =>
    d.status === 'CONFIRMED' || (d.status === 'FAILED' && d.lastErrorCode === 'UNKNOWN_OUTCOME');
  const rows = deliveries ?? [];
  if (scope === 'all') return rows.some(took);
  if (scope === 'items') return rows.some((d) => itemIds.includes(d.orderItemId) && took(d));
  return rows.some((d) => deliveryIds.includes(d.id) && took(d));
}

/** Toast outcome of a re-run response. */
export function rerunOutcome(body) {
  const created = Number(body?.created) || 0;
  const skipped = Number(body?.skipped) || 0;
  if (created === 0) return { key: 'modals.rerun.toast-none', values: {}, variant: 'info' };
  if (skipped > 0)
    return {
      key: 'modals.rerun.toast-skipped',
      values: { count: created, skipped },
      variant: 'success',
    };
  return { key: 'modals.rerun.toast', values: { count: created }, variant: 'success' };
}

// ---- resend mail ------------------------------------------------------------------------------

export const RESEND_KINDS = [
  'ORDER_CONFIRMATION',
  'GIFT_RECEIVED',
  'BANK_TRANSFER_INSTRUCTIONS',
  'ORDER_REFUNDED',
  'SHIPMENT_SHIPPED',
  'SHIPMENT_DELIVERED',
];
export const SHIPMENT_MAIL_KINDS = ['SHIPMENT_SHIPPED', 'SHIPMENT_DELIVERED'];
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const isEmail = (text) => clean(text).length <= 255 && EMAIL.test(clean(text));

/** Shipment mails name their shipment (`refId`); the recipient is optional (server default = order e-mail). */
export function resendRequest(orderId, { kind, recipient = '', shipmentId = null }) {
  const error = {};
  if (!RESEND_KINDS.includes(kind)) error.kind = true;
  if (clean(recipient) !== '' && !isEmail(recipient)) error.recipient = true;
  const shipmentMail = SHIPMENT_MAIL_KINDS.includes(kind);
  if (shipmentMail && !(Number.isInteger(shipmentId) && shipmentId > 0)) error.shipment = true;
  if (Object.keys(error).length > 0) return { error };
  const body = { kind };
  optional(body, 'recipient', recipient);
  if (shipmentMail) body.refId = shipmentId;
  return { request: req('POST', `/orders/${orderId}/mails/resend`, body) };
}

// ---- plain requests ---------------------------------------------------------------------------

export const revokeRequest = (orderId, itemIds = null) =>
  req(
    'POST',
    `/orders/${orderId}/revoke`,
    itemIds && itemIds.length > 0 ? { orderItemIds: [...itemIds] } : {},
  );
export const chargebackActionsRequest = (orderId) =>
  req('POST', `/orders/${orderId}/chargeback-actions`);
export const anonymizeRequest = (orderId) => req('POST', `/orders/${orderId}/anonymize`);
export const invoiceRegenerateRequest = (orderId) =>
  req('POST', `/orders/${orderId}/invoice/regenerate`);
export const refundRetryRequest = (refundId) => req('POST', `/refunds/${refundId}/retry`);
export const refundCancelRequest = (refundId) => req('POST', `/refunds/${refundId}/cancel`);
export const deliveryRetryRequest = (deliveryId) => req('POST', `/deliveries/${deliveryId}/retry`);
export const deliveryCancelRequest = (deliveryId) =>
  req('POST', `/deliveries/${deliveryId}/cancel`);
export const mailRetryRequest = (mailId) => req('POST', `/mails/${mailId}/retry`);
export const paymentQueryRequest = (paymentId) => req('POST', `/payments/${paymentId}/query`);
export const shipmentTrackRequest = (shipmentId) => req('POST', `/shipments/${shipmentId}/track`);
export const shipmentRetryRequest = (shipmentId) => req('POST', `/shipments/${shipmentId}/retry`);
export const shipmentCancelRequest = (shipmentId) => req('POST', `/shipments/${shipmentId}/cancel`);
export const exchangeRateRequest = (orderId, rate) =>
  req('PUT', `/orders/${orderId}/exchange-rate`, { exchangeRate: rate });
export const exchangeRateRefreshRequest = (orderId) =>
  req('POST', `/orders/${orderId}/exchange-rate/refresh`);

/** Order note (≤ 2000, 13 §6.1). `{ error: { note } }` when too long. */
export function noteRequest(orderId, note) {
  const text = String(note ?? '');
  if (text.length > ORDER_NOTE_MAX) return { error: { note: true } };
  return { request: req('PUT', `/orders/${orderId}/note`, { note: text }) };
}

export const orderPath = (orderId) => marketPath(`/orders/${orderId}`);
export const paymentEventsPath = (paymentId) => marketPath(`/payments/${paymentId}/events`);
export const shipmentLabelPath = (base, shipmentId, generic) =>
  `${base}/api/panel/market/shipments/${shipmentId}/label${generic ? '?generic=true' : ''}`;
