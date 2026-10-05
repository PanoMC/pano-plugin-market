// Pure model of the order page (14 §11.2, §11.3): load resolution, the view state, the status block,
// the extra lines, the totals rows and the item / shipment badges. No SDK, no DOM, no clock access
// (callers pass `now`). The theme never computes a total: every amount shown is a server number.
import { isSafeExternalUrl } from './paymentStart.js';

/** `publicId` as the order page accepts it (14 §11.2). */
export const ORDER_ID = /^[0-9A-Za-z]{20}$/;

/** Values of `?return=` (an untrusted hint from the gateway return redirect, 02 §7.3). */
export const RETURN_HINTS = ['success', 'cancel', 'pending'];

/** `CONFIRMING` falls back to `AWAITING_PAYMENT` this long after mount (14 §11.3). */
export const CONFIRMING_WINDOW_MS = 60 * 1000;

export const ORDER_TITLE_KEY = 'plugins.pano-plugin-market.theme.order.title';
export const ORDER_TITLE_PLAIN_KEY = 'plugins.pano-plugin-market.theme.order.title-plain';

export const PAID_STATUSES = ['COMPLETED', 'PARTIALLY_REFUNDED'];

/** View states in the order of the table of 14 §11.3. */
export const VIEW_STATES = [
  'PAID_DELIVERED',
  'PAID_SHIPPING',
  'PAID_DELIVERING',
  'PAID_DELIVERY_FAILED',
  'REVIEW',
  'PROCESSING',
  'CONFIRMING',
  'AWAITING_PAYMENT',
  'REFUNDED',
  'CHARGEBACK',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
];

/** Every view state a settled order can end in: polling stops there (14 §11.5). */
export const SETTLED_STATES = [
  'PAID_DELIVERED',
  'PAID_DELIVERY_FAILED',
  'REFUNDED',
  'CHARGEBACK',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
];

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const asNumber = (value) => (typeof value === 'number' && Number.isFinite(value) ? value : null);

// ---- load -------------------------------------------------------------------------------------------------

/** The decoded `params.id` when it is a plain 20-character id, else null (the page answers 404). */
export function parseOrderId(raw) {
  return typeof raw === 'string' && ORDER_ID.test(raw) ? raw : null;
}

/** `success | cancel | pending`, else null. */
export function parseReturnHint(raw) {
  return RETURN_HINTS.includes(raw) ? raw : null;
}

/**
 * The address-bar value without `token` and `return` (the access token must not stay in the URL, 14 §11.2).
 * `href` may be absolute or relative; the answer is `path?search#hash` and keeps every other parameter.
 */
export function stripOrderParams(href) {
  let url;
  try {
    url = new URL(String(href), 'http://localhost');
  } catch (e) {
    return '/';
  }

  url.searchParams.delete('token');
  url.searchParams.delete('return');

  return `${url.pathname}${url.search}${url.hash}`;
}

/** True when the address carries a token or a return hint that has to be removed. */
export function hasOrderParams(href) {
  try {
    const params = new URL(String(href), 'http://localhost').searchParams;

    return params.has('token') || params.has('return');
  } catch (e) {
    return false;
  }
}

/** A token the page may send as a header: printable ASCII only (no header injection), at most 256. */
export function isUsableToken(value) {
  return typeof value === 'string' && /^[\x21-\x7e]{1,256}$/.test(value);
}

/**
 * What to do with the access token after the order was fetched again with it (14 §11.2 step 2). `ok` = the server
 * answered, `limited` = the answer is still the limited view. Only a server answer that is still limited proves
 * the token wrong; a failed fetch (NETWORK, 429, 5xx) says nothing, so the token is kept. -> 'DROP' | 'KEEP'.
 */
export function tokenAfterRefetch({ ok, limited } = {}) {
  return ok === true && limited === true ? 'DROP' : 'KEEP';
}

/**
 * Result of the page load. `res` = ApiResult of GET orders/:publicId (the SSR load never forwards the token),
 * `id` = the validated id, `token` = `?token=`, `returnHint` = parseReturnHint(...).
 * -> `{ notFound: true }` (the page throws a 404) or `{ data, pageTitle, meta? }`.
 */
export function resolveOrderLoad({
  id,
  token = null,
  returnHint = null,
  res,
  settings = null,
  features = {},
}) {
  if (!id) return { notFound: true };
  if (res && res.ok === false && res.code === 'NOT_FOUND') return { notFound: true };

  const urlToken = isUsableToken(token) ? token : null;

  if (!res || !res.ok || !isObject(res.order)) {
    return {
      data: { state: 'ERROR', id, code: res?.code || 'NETWORK', urlToken, returnHint },
      pageTitle: { title: ORDER_TITLE_PLAIN_KEY },
    };
  }

  const order = res.order;
  const number = order.number;

  const result = {
    data: {
      state: 'READY',
      id,
      order,
      urlToken,
      returnHint,
      settings: settings || {},
    },
    pageTitle:
      number !== null && number !== undefined && number !== ''
        ? { title: ORDER_TITLE_KEY, titleValues: { number } }
        : { title: ORDER_TITLE_PLAIN_KEY },
  };

  if (features.meta === true) result.meta = { robots: 'noindex,nofollow', referrer: 'no-referrer' };

  return result;
}

// ---- view state -------------------------------------------------------------------------------------------

const STATE_PREFIX = 'theme.order.state.';

const PANELS_ALL_OFF = {
  items: false,
  totals: false,
  shipments: false,
  invoice: false,
  payment: false,
  instructions: false,
  cancel: false,
};

const PAID_PANELS = {
  ...PANELS_ALL_OFF,
  items: true,
  totals: true,
  shipments: true,
  invoice: true,
};
const PLAIN_PANELS = { ...PANELS_ALL_OFF, items: true, totals: true };

/** `fulfillmentStatus` / `shippingStatus` / payment status of an order, with the documented defaults. */
function facts(order) {
  return {
    status: order?.status,
    fulfillment: order?.fulfillmentStatus ?? 'NONE',
    shipping: order?.shippingStatus ?? 'NOT_REQUIRED',
    ps: order?.payment?.status,
  };
}

function make(state, fields) {
  return {
    state,
    variant: 'secondary',
    icon: 'fa-solid fa-circle-info',
    spinner: false,
    titleKey: `${STATE_PREFIX}${state.toLowerCase()}`,
    lineKey: null,
    subKey: null,
    showCountdown: false,
    backToStore: false,
    limited: false,
    settled: SETTLED_STATES.includes(state),
    panels: PLAIN_PANELS,
    ...fields,
  };
}

function paidView({ fulfillment, shipping }) {
  const fulfillmentOk = fulfillment === 'NONE' || fulfillment === 'FULFILLED';

  // 1
  if (fulfillmentOk && (shipping === 'NOT_REQUIRED' || shipping === 'DELIVERED'))
    return make('PAID_DELIVERED', {
      variant: 'success',
      icon: 'fa-solid fa-circle-check',
      titleKey: `${STATE_PREFIX}paid`,
      panels: PAID_PANELS,
    });

  // 2
  if (fulfillmentOk && ['PENDING', 'PARTIAL', 'SHIPPED', 'RETURNED'].includes(shipping))
    return make('PAID_SHIPPING', {
      variant: 'success',
      icon: 'fa-solid fa-truck',
      titleKey: `${STATE_PREFIX}shipping.${shipping}`,
      panels: PAID_PANELS,
    });

  // 3
  if (fulfillment === 'PENDING' || fulfillment === 'PARTIAL')
    return make('PAID_DELIVERING', {
      variant: 'success',
      icon: 'fa-solid fa-circle-check',
      titleKey: `${STATE_PREFIX}paid`,
      lineKey: `${STATE_PREFIX}delivering`,
      panels: PAID_PANELS,
    });

  // 4
  if (fulfillment === 'FAILED' || fulfillment === 'REVOKED')
    return make('PAID_DELIVERY_FAILED', {
      variant: 'warning',
      icon: 'fa-solid fa-triangle-exclamation',
      titleKey: `${STATE_PREFIX}${fulfillment === 'REVOKED' ? 'revoked' : 'delivery-failed'}`,
      panels: PAID_PANELS,
    });

  // A paid order with a combination the table does not name (a future enum value): shown as paid, and
  // polling stops there like for row 1.
  return make('PAID_DELIVERED', {
    variant: 'success',
    icon: 'fa-solid fa-circle-check',
    titleKey: `${STATE_PREFIX}paid`,
    panels: PAID_PANELS,
  });
}

/**
 * The view state of an order (14 §11.3), first matching row wins. `returnHint` = parseReturnHint(...) or null,
 * `now` = epoch ms from the clock store, `mountedAt` = epoch ms of the mount (0 / unknown = just mounted).
 * Returns the model of the status block:
 * `{ state, variant, icon, spinner, titleKey, lineKey, subKey, showCountdown, backToStore, limited, settled, panels }`
 * `panels` = which panels render (`items totals shipments invoice payment instructions cancel`). A `limited` order
 * shows only the status block, item names and totals, whatever the state (no payment, address, invoice, action).
 */
export function viewState(order, returnHint = null, now = 0, mountedAt = 0) {
  const { status, fulfillment, shipping, ps } = facts(order);
  const limited = order?.limited === true;
  const elapsed = mountedAt > 0 && now > 0 ? now - mountedAt : 0;

  let view;

  if (PAID_STATUSES.includes(status)) view = paidView({ fulfillment, shipping });
  else if (status === 'REVIEW')
    view = make('REVIEW', {
      variant: 'info',
      icon: 'fa-solid fa-hourglass-half',
      titleKey: `${STATE_PREFIX}review`,
    });
  else if (status === 'PENDING') {
    if (ps === 'PROCESSING')
      view = make('PROCESSING', {
        variant: 'info',
        spinner: true,
        titleKey: `${STATE_PREFIX}${order?.payment?.methodId === 'bank-transfer' ? 'processing-bank' : 'processing'}`,
        panels: { ...PLAIN_PANELS, instructions: true },
      });
    else if (
      (returnHint === 'success' || returnHint === 'pending') &&
      (ps === 'CREATED' || ps === 'PENDING') &&
      elapsed < CONFIRMING_WINDOW_MS
    )
      view = make('CONFIRMING', {
        variant: 'info',
        spinner: true,
        titleKey: `${STATE_PREFIX}confirming`,
      });
    else
      view = make('AWAITING_PAYMENT', {
        variant: 'warning',
        icon: 'fa-solid fa-credit-card',
        titleKey: `${STATE_PREFIX}awaiting`,
        subKey:
          returnHint === 'cancel'
            ? `${STATE_PREFIX}cancelled-at-gateway`
            : ps === 'FAILED' || ps === 'EXPIRED'
              ? `${STATE_PREFIX}attempt-failed`
              : null,
        showCountdown: true,
        panels: { ...PLAIN_PANELS, payment: true, cancel: true },
      });
  } else if (status === 'REFUNDED')
    view = make('REFUNDED', {
      variant: 'secondary',
      icon: 'fa-solid fa-rotate-left',
      titleKey: `${STATE_PREFIX}refunded`,
      panels: { ...PLAIN_PANELS, invoice: true },
    });
  else if (status === 'CHARGEBACK')
    view = make('CHARGEBACK', {
      variant: 'danger',
      icon: 'fa-solid fa-gavel',
      titleKey: `${STATE_PREFIX}chargeback`,
    });
  else if (status === 'FAILED')
    view = make('FAILED', {
      variant: 'danger',
      icon: 'fa-solid fa-circle-xmark',
      titleKey: `${STATE_PREFIX}FAILED`,
      backToStore: true,
    });
  else if (status === 'CANCELLED')
    view = make('CANCELLED', {
      variant: 'secondary',
      icon: 'fa-solid fa-ban',
      titleKey: `${STATE_PREFIX}CANCELLED`,
      backToStore: true,
    });
  else if (status === 'EXPIRED')
    view = make('EXPIRED', {
      variant: 'secondary',
      icon: 'fa-solid fa-clock',
      titleKey: `${STATE_PREFIX}EXPIRED`,
      backToStore: true,
    });
  else
    // a status this theme does not know: neutral block, polling stops (never a payment panel)
    view = make('UNKNOWN', {
      variant: 'secondary',
      icon: 'fa-solid fa-circle-question',
      titleKey: `${STATE_PREFIX}unknown`,
      settled: true,
    });

  if (limited) view = { ...view, limited: true, panels: PLAIN_PANELS };

  return view;
}

// ---- extra lines ------------------------------------------------------------------------------------------

/**
 * Lines shown in any state (14 §11.3): `{ refundPending, buyerActionUrl, testMode, isGift, recipientUsername }`.
 * `buyerActionUrl` is kept only when it is an absolute http(s) URL.
 */
export function orderExtras(order) {
  const limited = order?.limited === true;

  return {
    refundPending: !limited && order?.refundPending === true,
    buyerActionUrl:
      !limited && isSafeExternalUrl(order?.buyerActionUrl) ? order.buyerActionUrl : null,
    testMode: order?.testMode === true,
    isGift: order?.isGift === true,
    recipientUsername:
      typeof order?.recipientUsername === 'string' && order.recipientUsername !== ''
        ? order.recipientUsername
        : null,
  };
}

// ---- totals -----------------------------------------------------------------------------------------------

/**
 * Rows of the totals list from `order.totals`, in the order of the checkout summary (14 §10.5, §11.4):
 * `{ id, labelKey, amount, negative?, strong?, extra? }` plus "Refunded" when `refundedTotal > 0`. Every
 * `amount` is the server's number, nothing is added or subtracted here. A limited order carries only `{ total }`;
 * a recipient view carries no totals at all (an empty list).
 */
export function totalsRows(order, { pricesIncludeVat = false } = {}) {
  const t = order?.totals;
  if (!isObject(t)) return [];

  const positive = (value) => Number(value) > 0;
  const rows = [];

  if (asNumber(t.subtotal) !== null)
    rows.push({ id: 'subtotal', labelKey: 'theme.order.totals.subtotal', amount: t.subtotal });

  if (positive(t.discountTotal))
    rows.push({
      id: 'discounts',
      labelKey: 'theme.order.totals.discounts',
      amount: t.discountTotal,
      negative: true,
    });

  if (positive(t.upgradeDiscount))
    rows.push({
      id: 'upgrade',
      labelKey: 'theme.order.totals.upgrade',
      amount: t.upgradeDiscount,
      negative: true,
    });

  if (positive(t.couponDiscount))
    rows.push({
      id: 'coupon',
      labelKey: 'theme.order.totals.coupon',
      amount: t.couponDiscount,
      negative: true,
    });

  if (positive(t.creatorDiscount))
    rows.push({
      id: 'creator',
      labelKey: 'theme.order.totals.creator',
      amount: t.creatorDiscount,
      negative: true,
    });

  if (asNumber(t.shippingTotal) !== null && (order.shipping || positive(t.shippingTotal)))
    rows.push({ id: 'shipping', labelKey: 'theme.order.totals.shipping', amount: t.shippingTotal });

  if (positive(t.paymentFee))
    rows.push({ id: 'fee', labelKey: 'theme.order.totals.fee', amount: t.paymentFee });

  if (positive(t.vatTotal))
    rows.push({
      id: 'vat',
      labelKey: pricesIncludeVat ? 'theme.checkout.vat-included' : 'theme.checkout.vat-added',
      amount: t.vatTotal,
    });

  if (asNumber(t.total) !== null)
    rows.push({ id: 'total', labelKey: 'theme.order.totals.total', amount: t.total, strong: true });

  if (positive(t.creditAmount))
    rows.push({
      id: 'credits',
      labelKey: 'theme.order.totals.credits-used',
      amount: t.creditValue,
      negative: true,
      extra: { credits: t.creditAmount, name: order?.credits?.name ?? '' },
    });

  if (asNumber(t.gatewayAmount) !== null && positive(t.creditAmount) && t.gatewayAmount !== t.total)
    rows.push({
      id: 'to-pay',
      labelKey: 'theme.order.totals.to-pay',
      amount: t.gatewayAmount,
      strong: true,
    });

  if (positive(t.refundedTotal))
    rows.push({
      id: 'refunded',
      labelKey: 'theme.order.totals.refunded',
      amount: t.refundedTotal,
      negative: true,
    });

  return rows;
}

// ---- items and shipments ----------------------------------------------------------------------------------

const DELIVERY_BADGES = {
  PENDING: { cls: 'text-bg-warning', labelKey: 'theme.order.delivery.PENDING' },
  PARTIAL: { cls: 'text-bg-info', labelKey: 'theme.order.delivery.PARTIAL' },
  FULFILLED: { cls: 'text-bg-success', labelKey: 'theme.order.delivery.FULFILLED' },
  FAILED: { cls: 'text-bg-danger', labelKey: 'theme.order.delivery.FAILED' },
  REVOKED: { cls: 'text-bg-secondary', labelKey: 'theme.order.delivery.REVOKED' },
};

/** Badge of an item's `delivery` (`NONE` / unknown = none): `{ cls, labelKey }` or null. `DELIVERED` reads as `FULFILLED`. */
export function deliveryBadge(delivery) {
  const key = delivery === 'DELIVERED' ? 'FULFILLED' : delivery;

  return DELIVERY_BADGES[key] ?? null;
}

/**
 * The buyer's answers of an item as label / value pairs. `fieldValues` is an object (`{ label: value }`) or an
 * array of `{ label | name | key, value }`; empty values and objects are dropped, values are plain strings.
 */
export function fieldPairs(fieldValues) {
  const text = (value) => {
    if (value === null || value === undefined || typeof value === 'object') return '';
    if (typeof value === 'boolean') return value ? 'true' : 'false';

    return String(value).trim().slice(0, 500);
  };

  let pairs = [];

  if (Array.isArray(fieldValues))
    pairs = fieldValues
      .filter(isObject)
      .map((entry) => [entry.label ?? entry.name ?? entry.key ?? entry.id, entry.value]);
  else if (isObject(fieldValues)) pairs = Object.entries(fieldValues);

  return pairs
    .map(([label, value]) => ({ label: text(label), value: text(value) }))
    .filter((pair) => pair.label !== '' && pair.value !== '');
}

const SHIPMENT_VARIANTS = {
  CREATED: 'text-bg-secondary',
  LABEL_READY: 'text-bg-secondary',
  IN_TRANSIT: 'text-bg-info',
  OUT_FOR_DELIVERY: 'text-bg-info',
  DELIVERED: 'text-bg-success',
  EXCEPTION: 'text-bg-warning',
  RETURNING: 'text-bg-warning',
  RETURNED: 'text-bg-warning',
  CANCELLED: 'text-bg-secondary',
  LOST: 'text-bg-danger',
};

/** Badge of a shipment status (14 §11.4): `{ cls, labelKey }`; an unknown status reads as `UNKNOWN`. */
export function shipmentBadge(status) {
  const known = Object.hasOwn(SHIPMENT_VARIANTS, status);

  return {
    cls: known ? SHIPMENT_VARIANTS[status] : 'text-bg-secondary',
    labelKey: `theme.order.shipment.${known ? status : 'UNKNOWN'}`,
  };
}

/** A tracking link is an absolute http(s) URL (it opens in a new tab with rel="noopener noreferrer"). */
export function safeTrackingUrl(value) {
  return isSafeExternalUrl(value) ? value : null;
}

/** `/api/market/orders/<id>/invoice` for the plain download link of a session owner without a token. */
export function invoicePath(id) {
  return `/api/market/orders/${id}/invoice`;
}

/** True when the buyer needs the blob download (a token is known: the plain link cannot carry the header). */
export function invoiceNeedsBlob(token) {
  return isUsableToken(token);
}

/** The countdown line of the awaiting-payment status block: ms left until `expiresAt`, null when unknown. */
export function expiryLeft(order, now) {
  const end = Number(order?.expiresAt);
  if (!Number.isFinite(end) || end <= 0 || !(now > 0)) return null;

  return Math.max(0, end - now);
}

// ---- addresses --------------------------------------------------------------------------------------------

/**
 * Plain text lines of an Address object (01 §5.6) for an `address` block: name, company, street, locality, phone.
 * The identity number is never shown (it is only needed by the carrier / invoice). The country is added by the
 * component (it is localised there). An object without any of these fields gives an empty list.
 */
export function addressLines(address) {
  if (!isObject(address)) return [];

  const clean = (value) => (typeof value === 'string' ? value.trim() : '');
  const join = (...parts) => parts.map(clean).filter(Boolean).join(' ');

  const locality = [
    join(address.neighborhood),
    join(address.district),
    join(address.postalCode, address.city),
    join(address.state),
  ].filter(Boolean);

  return [
    join(address.firstName, address.lastName),
    join(address.company),
    join(address.line1),
    join(address.line2),
    locality.join(', '),
    address.type === 'COMPANY' && (clean(address.taxNumber) || clean(address.taxOffice))
      ? join(address.taxNumber, clean(address.taxOffice) ? `(${clean(address.taxOffice)})` : '')
      : '',
    join(address.phone),
  ].filter(Boolean);
}
