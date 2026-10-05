// API call helpers of the market panel (13 §3.3). Pure: no Svelte, no SDK import, so they can be
// unit tested; callers pass in the promise that ApiUtil returned.
import { uuid } from './uuid.js';

/** Every code of 04 §11, the platform codes the panel reuses, and the panel's own NETWORK_ERROR. */
export const KNOWN_ERRORS = new Set([
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
  'NETWORK_ERROR',
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
]);

/** Panel API base; every path in the spec is relative to it unless it starts with /api. */
export const API_BASE = '/api/panel/market';
export const marketPath = (path) => (path.startsWith('/api') ? path : API_BASE + path);

/**
 * Normalises one ApiUtil call. `promise` = ApiUtil.get/post/put/delete(...).
 * A falsy or non-object body (demo mode, swallowed request, or the raw text ApiUtil returns for a
 * non-JSON reply such as a proxy 502 page) or a rejected promise is NETWORK_ERROR: the request may
 * or may not have been executed, so the caller must keep its idempotency state. A Blob (CSV
 * export) is an object and passes.
 */
export async function call(promise) {
  let body;
  try {
    body = await promise;
  } catch {
    return { ok: false, error: 'NETWORK_ERROR', body: {} };
  }
  if (!body || typeof body !== 'object') return { ok: false, error: 'NETWORK_ERROR', body: {} };
  if (body.error) return { ok: false, error: body.error, body };
  return { ok: true, body };
}

/** Locale key (relative to the plugin root) of an error code; unknown codes are errors.UNKNOWN. */
export function errorKey(code) {
  return KNOWN_ERRORS.has(code) ? 'errors.' + code : 'errors.UNKNOWN';
}

/** i18n values for an error toast. retryAfter is a number, never API text. */
export function errorParams(code, body) {
  if (code === 'TOO_MANY_REQUESTS' || code === 'CODE_ATTEMPTS_LOCKED') {
    const seconds = Number(body?.retryAfter);
    return { values: { seconds: Number.isFinite(seconds) ? seconds : 0 } };
  }
  return {};
}

/** The idempotency state a modal / page creates when it opens. */
export function newIdempotency() {
  return { key: null, fingerprint: null };
}

/** Same body => same key (retry, double click); a changed body => a new key. */
export function idempotencyKeyFor(state, body) {
  const fingerprint = JSON.stringify(body);
  if (state.key === null || state.fingerprint !== fingerprint) {
    state.key = uuid();
    state.fingerprint = fingerprint;
  }
  return state.key;
}

/** After a success (or IDEMPOTENCY_CONFLICT) the state starts over. */
export function resetIdempotency(state) {
  state.key = null;
  state.fingerprint = null;
}
