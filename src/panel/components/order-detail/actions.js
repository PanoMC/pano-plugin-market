// Action rules of the order detail page (13 §6.1, §6.2, §9.2, §20.1). Pure: no Svelte, no SDK import.
// UI gating is cosmetic (13 §1.10): every endpoint enforces its node again; an item needs the
// `allowed{}` flag of the response AND the node of the user.
import { can } from '../../utils/permissions.js';

/**
 * Items of the page-level dropdown, in display order (13 §6.2). `flag` = key of `allowed{}`,
 * `node` = permission key, `kind` = how the item is run: `modal` (own modal), `confirm`
 * (ConfirmModal) or `external` (a modal another slice owns, see external.js).
 */
export const ACTION_DEFS = [
  {
    id: 'markPaid',
    flag: 'markPaid',
    node: 'PAY',
    kind: 'modal',
    modal: 'status',
    icon: 'fa-circle-check',
  },
  {
    id: 'cancel',
    flag: 'cancel',
    node: 'PAY',
    kind: 'modal',
    modal: 'status',
    icon: 'fa-ban',
    danger: true,
  },
  {
    id: 'markFailed',
    flag: 'cancel',
    node: 'PAY',
    kind: 'modal',
    modal: 'status',
    icon: 'fa-circle-xmark',
    danger: true,
  },
  {
    id: 'review',
    flag: 'review',
    node: 'PAY',
    kind: 'modal',
    modal: 'review',
    icon: 'fa-clipboard-check',
  },
  {
    id: 'approveTransfer',
    flag: 'bankTransfer',
    node: 'PAY',
    kind: 'modal',
    modal: 'status',
    icon: 'fa-building-columns',
  },
  {
    id: 'rejectTransfer',
    flag: 'bankTransfer',
    node: 'PAY',
    kind: 'modal',
    modal: 'status',
    icon: 'fa-building-circle-xmark',
    danger: true,
  },
  { id: 'refund', flag: 'refund', node: 'PAY', kind: 'external', icon: 'fa-rotate-left' },
  {
    id: 'dispute',
    flag: 'dispute',
    node: 'PAY',
    kind: 'modal',
    modal: 'dispute',
    icon: 'fa-gavel',
  },
  {
    id: 'rerunDelivery',
    flag: 'rerunDelivery',
    node: 'OM',
    kind: 'modal',
    modal: 'rerun',
    icon: 'fa-rotate-right',
  },
  {
    id: 'revoke',
    flag: 'revoke',
    node: 'OM',
    kind: 'confirm',
    icon: 'fa-user-slash',
    danger: true,
  },
  {
    id: 'editShippingAddress',
    flag: 'editShippingAddress',
    node: 'OM',
    kind: 'external',
    icon: 'fa-location-dot',
  },
  {
    id: 'runChargebackActions',
    flag: 'runChargebackActions',
    node: 'PAY',
    kind: 'confirm',
    icon: 'fa-bolt',
    danger: true,
  },
  {
    id: 'anonymize',
    flag: 'anonymize',
    node: 'PAY',
    kind: 'confirm',
    icon: 'fa-user-secret',
    danger: true,
  },
  { id: 'createShipment', flag: 'createShipment', node: 'OM', kind: 'external', icon: 'fa-truck' },
  {
    id: 'resendMail',
    flag: 'resendMail',
    node: 'OM',
    kind: 'modal',
    modal: 'resend',
    icon: 'fa-envelope',
  },
];

/** Every flag of `allowed{}` (04 §7) that gates a dropdown item; `refundMax` / `refundModes` belong to RefundModal. */
export const ACTION_FLAGS = [...new Set(ACTION_DEFS.map((d) => d.flag))];

/** The visible items for this order and user (flag AND node); empty = no dropdown. */
export function orderActions(detail, user) {
  const allowed = detail?.allowed;
  if (!allowed || typeof allowed !== 'object') return [];
  return ACTION_DEFS.filter((def) => allowed[def.flag] === true && can(user, def.node));
}

/**
 * Drops `external` items whose modal is not wired in (external.js): a dead button is worse than
 * no button. `available` = { refund: Component | null, ... }.
 */
export function withAvailable(actions, available = {}) {
  return actions.filter((a) => a.kind !== 'external' || Boolean(available[a.id]));
}

/** Codes after which the loaded flags were stale: the page shows the toast and refreshes (13 §6.2). */
export const STALE_ERRORS = new Set([
  'INVALID_ORDER_TRANSITION',
  'INVALID_STATE',
  'NOT_FOUND',
  'PROVIDER_UNAVAILABLE',
  'DELIVERY_NOT_RETRYABLE',
  'DELIVERY_NOT_CANCELLABLE',
  'SHIPMENT_NOT_CANCELLABLE',
  'INVALID_SHIPMENT_TRANSITION',
  'ORDER_NOT_SHIPPABLE',
  'SUBSCRIPTION_NOT_RETRYABLE',
]);

export const isStaleError = (code) => STALE_ERRORS.has(code);

// ---- row level --------------------------------------------------------------------------------

const OPEN_PAYMENT = ['CREATED', 'PENDING', 'PROCESSING'];

/** Items of the Items-card control dropdown (OM). The bundle line itself carries no actions. */
export function itemActions(item, detail, user) {
  if (!item || !can(user, 'OM') || item.kind === 'BUNDLE') return [];
  const out = [];
  if (detail?.allowed?.rerunDelivery === true) out.push('rerun');
  if (detail?.allowed?.revoke === true) out.push('revoke');
  return out;
}

/** Payment attempt row: `events` always, `query` (OM) while the attempt is open. */
export function paymentActions(payment, user) {
  const out = ['events'];
  if (can(user, 'OM') && OPEN_PAYMENT.includes(payment?.status)) out.push('query');
  return out;
}

/** Refund row (PAY): Retry for FAILED, Cancel for REQUESTED / PENDING. */
export function refundActions(refund, user) {
  if (!can(user, 'PAY')) return [];
  if (refund?.status === 'FAILED') return ['retry'];
  if (refund?.status === 'REQUESTED' || refund?.status === 'PENDING') return ['cancel'];
  return [];
}

/** Dispute row (PAY, OPEN only): the three resolutions of PUT /disputes/:id. */
export function disputeActions(dispute, user) {
  if (!can(user, 'PAY') || dispute?.status !== 'OPEN') return [];
  return ['WON', 'LOST', 'CLOSED'];
}

/** Mail row (OM): Retry for FAILED / SKIPPED. */
export function mailActions(mail, user) {
  if (!can(user, 'OM')) return [];
  return mail?.status === 'FAILED' || mail?.status === 'SKIPPED' ? ['retry'] : [];
}

const NOT_RETRYABLE_CODES = new Set([
  'RENDER_ERROR',
  'NO_TARGET_SERVER',
  'SERVER_REMOVED',
  'INVALID_PLAYER',
]);
const RETRY_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;
const CANCELLABLE = ['PENDING', 'SCHEDULED', 'WAITING_SERVER', 'SENT', 'QUEUED'];

/** FAILED rows except the four codes and rows whose `sentAt` is older than 30 days; WAITING_SERVER; SENT. */
export function deliveryRetryable(delivery, now = Date.now()) {
  if (!delivery) return false;
  if (delivery.status === 'WAITING_SERVER' || delivery.status === 'SENT') return true;
  if (delivery.status !== 'FAILED') return false;
  if (NOT_RETRYABLE_CODES.has(delivery.lastErrorCode)) return false;
  const sentAt = Number(delivery.sentAt);
  return !(Number.isFinite(sentAt) && sentAt > 0 && now - sentAt > RETRY_WINDOW_MS);
}

/** Delivery row (OM, 13 §9.2): `retry` (SENT is labelled "offer again now" by the view), `cancel`. */
export function deliveryActions(delivery, user, now = Date.now()) {
  if (!can(user, 'OM')) return [];
  const out = [];
  if (deliveryRetryable(delivery, now))
    out.push(delivery.status === 'SENT' ? 'offer-again' : 'retry');
  if (CANCELLABLE.includes(delivery?.status)) out.push('cancel');
  return out;
}

const CANCELLABLE_SHIPMENT = ['CREATED', 'LABEL_READY'];

/** Shipment row (OM, 13 §20.1). `label` = stored label, `generic-label` otherwise. */
export function shipmentActions(shipment, user) {
  if (!can(user, 'OM') || !shipment) return [];
  const out = [shipment.labelFile ? 'label' : 'generic-label', 'track'];
  if (shipment.status === 'CREATED' && (shipment.lastErrorCode || shipment.lastError))
    out.push('retry');
  if (CANCELLABLE_SHIPMENT.includes(shipment.status)) out.push('cancel');
  return out;
}

/** Invoice links carry billing data: OM or PAY only (13 §6). */
export const canSeeInvoices = (user) => can(user, 'OM', 'PAY');

/** Invoices card: hidden when invoicing is off and nothing was issued. */
export function showInvoicesCard(detail, ctx, user) {
  const invoices = detail?.invoices ?? [];
  if (!canSeeInvoices(user) && invoices.length === 0) return false;
  return ctx?.invoiceEnabled === true || invoices.length > 0;
}

/** The statuses a REVOKE / revoke-all button can still act on is the server's call; `allowed.revoke` decides. */
export const canEditNote = (user) => can(user, 'OM');
export const canEditExchangeRate = (user) => can(user, 'PAY');
