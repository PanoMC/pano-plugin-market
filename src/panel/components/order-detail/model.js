// Display model of the order detail page (13 §6.1). Pure: no Svelte, no SDK import.

const EPS = 0.005;
const num = (value) => {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
};
const isZero = (value) => Math.abs(num(value)) < EPS;
const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

/** `/orders/detail/[id]` param: digits only, > 0; null otherwise (the page then shows NOT_FOUND). */
export function parseOrderId(raw) {
  const text = String(raw ?? '').trim();
  if (!/^\d+$/.test(text)) return null;
  const id = Number(text);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * Rows of the Totals card in display order. Rows whose value is 0 are omitted except Subtotal and
 * Total. `kind`: money | credits | text. `negative` rows print "−amount". `note` is plain text
 * (code / method name), never HTML.
 */
export function totalsRows(order) {
  if (!order) return [];
  const rows = [];
  const money = (key, amount, extra = {}) =>
    rows.push({ key, kind: 'money', amount: num(amount), ...extra });
  const optional = (key, amount, extra) => {
    if (!isZero(amount)) money(key, amount, extra);
  };

  money('subtotal', order.subtotal, { always: true });
  optional('discounts', order.discountTotal, { negative: true });
  optional('coupon', order.couponDiscount, { negative: true, note: order.couponCode || null });
  optional('creator-code', order.creatorDiscount, {
    negative: true,
    note: order.creatorCode || null,
  });
  optional('upgrade', order.upgradeDiscount, { negative: true });
  optional('shipping', order.shippingTotal, { note: order.shippingMethodName || null });
  optional('payment-fee', order.paymentFee);
  optional('vat', order.vatTotal, { included: order.pricesIncludeVat === true });
  money('total', order.totalPrice, { always: true, strong: true });
  if (!isZero(order.creditAmount))
    rows.push({
      key: 'paid-credits',
      kind: 'credits',
      amount: num(order.creditAmount),
      value: num(order.creditValue),
    });
  optional('paid-gateway', order.gatewayAmount);
  if (
    !isZero(order.paidAmount) &&
    Math.abs(num(order.paidAmount) - num(order.gatewayAmount)) >= EPS
  )
    money('collected', order.paidAmount);
  if (!isZero(order.refundedTotal))
    rows.push({
      key: 'refunded',
      kind: 'money',
      amount: num(order.refundedTotal),
      negative: true,
      gateway: num(order.refundedGatewayAmount),
      credits: num(order.refundedCreditAmount),
    });
  if (present(order.displayCurrency))
    rows.push({
      key: 'display',
      kind: 'text',
      currency: order.displayCurrency,
      rate: order.displayRate ?? null,
    });
  return rows;
}

/** `pricingMode ≠ MARKET` marks external pricing. */
export const isExternalPricing = (order) =>
  present(order?.pricingMode) && order.pricingMode !== 'MARKET';

/** Value of the exchange-rate input: a number > 0 (`,` or `.`), else null. */
export function parseExchangeRate(text) {
  const value = String(text ?? '')
    .trim()
    .replace(',', '.');
  if (!/^\d+(\.\d+)?$/.test(value)) return null;
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

/** BUNDLE_CHILD rows are indented and carry no price. */
export const isBundleChild = (item) => item?.kind === 'BUNDLE_CHILD';

/** The list price is struck through when it differs from the unit price (and exists). */
export const showListPrice = (item) =>
  !isBundleChild(item) &&
  num(item?.listUnitPrice) > 0 &&
  Math.abs(num(item.listUnitPrice) - num(item.unitPrice)) >= EPS;

/**
 * Delivery cell of an item (13 §6.1): no rows = none; any FAILED = failed; all CONFIRMED|SENT = fulfilled;
 * else pending.
 */
export function itemDeliveryState(itemId, deliveries) {
  const rows = (deliveries ?? []).filter((d) => d.orderItemId === itemId);
  if (rows.length === 0) return 'none';
  if (rows.some((d) => d.status === 'FAILED')) return 'failed';
  if (rows.every((d) => d.status === 'CONFIRMED' || d.status === 'SENT')) return 'fulfilled';
  return 'pending';
}

/** Items card header text of the delivery state. */
export const DELIVERY_STATE_KIND = { fulfilled: 'success', failed: 'danger', pending: 'warning' };

/** Delivery that already took effect: re-running it grants again (credits, items) and needs PAY. */
export const deliveryTookEffect = (d) =>
  d?.status === 'CONFIRMED' || (d?.status === 'FAILED' && d?.lastErrorCode === 'UNKNOWN_OUTCOME');

/** A FAILED row that may have run (UNKNOWN_OUTCOME) is labelled "may have run" (13 §3.5). */
export const mayHaveRun = (d) => d?.status === 'FAILED' && d?.lastErrorCode === 'UNKNOWN_OUTCOME';

/** `revokePending` > 0 on a PARTIAL fulfillment shows "revoke pending" (13 §3.5). */
export const revokePending = (detail) =>
  detail?.order?.fulfillmentStatus === 'PARTIAL' && num(detail?.revokePending) > 0;

// ---- auto refresh -----------------------------------------------------------------------------

export const AUTO_REFRESH_MS = 15000;
export const AUTO_REFRESH_MAX = 40;

const OPEN_ORDER = ['PENDING', 'REVIEW'];
const OPEN_PAYMENT = ['CREATED', 'PENDING', 'PROCESSING'];
const OPEN_REFUND = ['REQUESTED', 'PENDING'];
const OPEN_DELIVERY = ['PENDING', 'SENDING', 'SENT', 'QUEUED'];

/** True while the order is still moving: any of the conditions of 13 §6. */
export function autoRefreshNeeded(detail) {
  if (!detail) return false;
  return (
    OPEN_ORDER.includes(detail.order?.status) ||
    (detail.payments ?? []).some((p) => OPEN_PAYMENT.includes(p.status)) ||
    (detail.refunds ?? []).some((r) => OPEN_REFUND.includes(r.status)) ||
    (detail.deliveries ?? []).some((d) => OPEN_DELIVERY.includes(d.status))
  );
}

/** One tick of the auto refresh: visible document, no open modal, below the cap, still needed. */
export function shouldAutoRefresh({ needed, visible, modalOpen, runs }) {
  return needed === true && visible === true && modalOpen !== true && runs < AUTO_REFRESH_MAX;
}

// ---- timeline, addresses, invoices ------------------------------------------------------------

export const ORDER_EVENT_TYPES = [
  'CREATED',
  'STATUS_CHANGED',
  'PAYMENT_STARTED',
  'PAYMENT_SUCCEEDED',
  'PAYMENT_FAILED',
  'PAYMENT_CANCELLED',
  'BANK_TRANSFER_NOTIFIED',
  'REVIEW_OPENED',
  'REFUND_REQUESTED',
  'REFUND_SUCCEEDED',
  'REFUND_FAILED',
  'DISPUTE_INQUIRY',
  'DISPUTE_OPENED',
  'DISPUTE_CLOSED',
  'BLOCK_CREATED',
  'BLOCK_REMOVED',
  'CLAWBACK_SHORTFALL',
  'DELIVERY_RERUN',
  'DELIVERY_FAILED',
  'DELIVERY_REVOKED',
  'SHIPMENT_CREATED',
  'SHIPMENT_UPDATED',
  'SHIPMENT_CANCELLED',
  'SUBSCRIPTION_STARTED',
  'SUBSCRIPTION_RENEWED',
  'SUBSCRIPTION_PAST_DUE',
  'SUBSCRIPTION_CHARGE_FAILED',
  'SUBSCRIPTION_CANCEL_REQUESTED',
  'SUBSCRIPTION_RESUMED',
  'SUBSCRIPTION_ENDED',
  'SUBSCRIPTION_REMOTE_CANCEL_FAILED',
  'MAIL_QUEUED',
  'NOTE',
];
export const ACTOR_TYPES = ['SYSTEM', 'BUYER', 'ADMIN', 'GATEWAY'];

/** Events newest first (createdAt, then id); the input is not mutated. */
export function timelineOf(events) {
  return [...(events ?? [])].sort(
    (a, b) => num(b.createdAt) - num(a.createdAt) || num(b.id) - num(a.id),
  );
}

export const isKnownEventType = (type) => ORDER_EVENT_TYPES.includes(type);
export const isKnownActor = (type) => ACTOR_TYPES.includes(type);

const ADDRESS_ORDER = [
  'company',
  'line1',
  'line2',
  'neighborhood',
  'district',
  'city',
  'state',
  'postalCode',
  'country',
  'phone',
];
const BILLING_EXTRA = ['type', 'taxOffice', 'taxNumber', 'identityNumber'];

/**
 * Address as text lines (13 §6.1): `firstName lastName`, then company ... phone, in that order, empty
 * parts skipped. `extra` (billing only) = labelled values `type`, `taxOffice`, `taxNumber`, `identityNumber`.
 */
export function addressLines(address, { billing = false } = {}) {
  if (!address || typeof address !== 'object') return { lines: [], extra: [] };
  const name = [address.firstName, address.lastName].filter(present).join(' ');
  const lines = [name, ...ADDRESS_ORDER.map((k) => address[k])].filter(present).map(String);
  const extra = billing
    ? BILLING_EXTRA.filter((k) => present(address[k])).map((k) => ({
        key: k,
        value: String(address[k]),
      }))
    : [];
  return { lines, extra };
}

/** Invoice / credit-note link of the panel API; `base` = router base. */
export function invoiceUrl(base, orderId, invoice) {
  const query = new URLSearchParams({ type: String(invoice?.type ?? '') });
  if (invoice?.refundId) query.set('refundId', String(invoice.refundId));
  return `${base}/api/panel/market/orders/${orderId}/invoice?${query.toString()}`;
}

/** Remaining collected gateway money = default dispute amount. */
export function remainingCollected(order) {
  return Math.max(
    0,
    Math.round((num(order?.paidAmount) - num(order?.refundedGatewayAmount)) * 100) / 100,
  );
}

/** Request / response bodies of the payment events modal (JSON or text) as display text. */
export function asText(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/** Resolved admin name of an event: only when the response names the actor. */
export const eventActorName = (event) => (event?.actorUsername ? String(event.actorUsername) : '');

/** Latest-first dates for the table cells. */
export const paymentDate = (p) => p?.paidAt ?? p?.createdAt ?? null;
export const paymentMessage = (p) => p?.adminMessage ?? p?.failureMessage ?? '';

// ---- item details -----------------------------------------------------------------------------

/** Label of a custom field value: the snapshot's label when it carries one, else the field key. */
export function fieldLabel(item, key) {
  const snap = item?.snapshot;
  const direct = snap?.fieldLabels?.[key];
  if (present(direct)) return String(direct);
  const fields = Array.isArray(snap?.fields) ? snap.fields : [];
  const hit = fields.find((f) => f?.fieldKey === key || f?.key === key);
  const label = hit?.name ?? hit?.label;
  return present(label) ? String(label) : key;
}

/** `[{ key, label, value }]` of an item's custom field values; empty values are skipped, values are text. */
export function fieldEntries(item) {
  const values = item?.fieldValues;
  if (!values || typeof values !== 'object') return [];
  return Object.entries(values)
    .filter(([, v]) => present(v))
    .map(([key, v]) => ({
      key,
      label: fieldLabel(item, key),
      value: typeof v === 'object' ? asText(v) : String(v),
    }));
}

/** Target server name of an item: the row's own name, else the name of one of its deliveries, else `#id`. */
export function itemTargetServer(item, deliveries) {
  if (present(item?.targetServerName)) return String(item.targetServerName);
  const hit = (deliveries ?? []).find((d) => d.orderItemId === item?.id && present(d.serverName));
  if (hit) return String(hit.serverName);
  return item?.targetServerId ? `#${item.targetServerId}` : '';
}

/** Unit label of an item row: `Product`, variant and sku are shown on separate lines by the view. */
export const itemRefunded = (item) => num(item?.refundedQuantity) > 0;

// ---- refunds ----------------------------------------------------------------------------------

export const REFUND_ORIGINS = ['PANEL', 'GATEWAY', 'SYSTEM'];
export const isKnownRefundOrigin = (origin) => REFUND_ORIGINS.includes(origin);

/** The second line (gateway / credits) shows whenever credits were part of the refund. */
export const refundHasSplit = (refund) => num(refund?.creditAmount) > 0;

// ---- deliveries -------------------------------------------------------------------------------

export const ACTION_TYPES = ['CREDIT', 'PERMISSION', 'COMMAND', 'WEBHOOK'];
export const PHASES = ['GRANT', 'RENEW', 'EXPIRE', 'REVOKE'];
export const DELIVERY_ERRORS = [
  'RENDER_ERROR',
  'NO_TARGET_SERVER',
  'INVALID_PLAYER',
  'NO_ACCOUNT',
  'NEEDS_CONFIRMATION',
  'DB_ERROR',
  'SERVER_OFFLINE',
  'COMPONENT_MISSING',
  'VERSION_MISMATCH',
  'SERVER_REMOVED',
  'UNKNOWN_OUTCOME',
  'ONLINE_WAIT_EXPIRED',
  'COMMAND_ERROR',
  'LUCKPERMS_MISSING',
  'DISABLED_LOCALLY',
  'INVALID_PAYLOAD',
  'REJECTED',
  'WEBHOOK_DEAD',
  'CANCELLED_BY_ADMIN',
  'ORDER_REVOKED',
  'ENTITLEMENT_ENDED',
  'NOTHING_TO_REVOKE',
];
export const isKnownActionType = (v) => ACTION_TYPES.includes(v);
export const isKnownPhase = (v) => PHASES.includes(v);
export const isKnownDeliveryError = (v) => DELIVERY_ERRORS.includes(v);

/** Tracking links are rendered only for http(s) URLs (a `javascript:` URL from a carrier never becomes an href). */
export function isHttpUrl(value) {
  if (!present(value)) return false;
  try {
    const url = new URL(String(value));
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

export const DISPUTE_ORIGINS = ['GATEWAY', 'MANUAL'];
export const isKnownDisputeOrigin = (v) => DISPUTE_ORIGINS.includes(v);

export const MAIL_KINDS = [
  'ORDER_RECEIVED',
  'ORDER_CONFIRMATION',
  'ORDER_DELIVERED',
  'GIFT_RECEIVED',
  'BANK_TRANSFER_INSTRUCTIONS',
  'ORDER_REFUNDED',
  'SUBSCRIPTION_REMINDER',
  'SUBSCRIPTION_PAYMENT_FAILED',
  'SUBSCRIPTION_CANCELLED',
  'SUBSCRIPTION_ENDED',
  'EXPIRY_REMINDER',
  'SHIPMENT_SHIPPED',
  'SHIPMENT_DELIVERED',
];
export const isKnownMailKind = (v) => MAIL_KINDS.includes(v);

export const ORDER_SOURCES = [
  'STOREFRONT',
  'PANEL',
  'GIFT_CODE',
  'RENEWAL',
  'EXTERNAL',
  'INGAME',
  'LEGACY',
];
export const isKnownOrderSource = (v) => ORDER_SOURCES.includes(v);

export const INVOICE_TYPES = ['INVOICE', 'CREDIT_NOTE'];
export const isKnownInvoiceType = (v) => INVOICE_TYPES.includes(v);
