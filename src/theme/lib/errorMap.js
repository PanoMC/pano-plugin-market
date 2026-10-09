// Error and message code -> text and checkout action (14 §10.9, §16). Pure: no SDK, no DOM.
// `messageKey(code)` is the only way a code becomes text: a code outside the key set (generated from 04 §11)
// answers theme.errors.GENERIC, so a raw identifier never reaches the buyer.

/** HTTP error codes of 04 §11 (and the platform codes market reuses) that can reach a storefront page. */
export const HTTP_CODES = [
  'BAD_REQUEST',
  'NOT_FOUND',
  'PAGE_NOT_FOUND',
  'NOT_LOGGED_IN',
  'NO_PERMISSION',
  'INVALID_CSRF_TOKEN',
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
  'INVALID_CREDIT_AMOUNT',
  'BUYER_BLOCKED',
  'OUT_OF_STOCK',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'PRODUCT_REQUIREMENT_NOT_MET',
  'PRICE_CHANGED',
  'IDEMPOTENCY_CONFLICT',
  'CREDITS_DISABLED',
  'ORDER_NOT_PAYABLE',
  'ORDER_NOT_CANCELLABLE',
  'SUBSCRIPTION_NOT_CANCELLABLE',
  'SUBSCRIPTION_NOT_RESUMABLE',
  'SUBSCRIPTION_NOT_RETRYABLE',
  'SUBSCRIPTION_NOT_MANAGEABLE',
  'TOO_MANY_REQUESTS',
  'CODE_ATTEMPTS_LOCKED',
  'PAYMENT_PROVIDER_ERROR',
  'STORE_DISABLED',
  'STORE_UNAVAILABLE',
  'STORE_BUSY',
];

/** Line / quote message codes of the last paragraph of 04 §11 (not HTTP errors). */
export const MESSAGE_CODES = [
  'PRODUCT_UNAVAILABLE',
  'VARIANT_REQUIRED',
  'VARIANT_UNAVAILABLE',
  'OUT_OF_STOCK',
  'QUANTITY_REDUCED',
  'MAX_QUANTITY',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'REQUIREMENT_NOT_MET',
  'PERMISSION_REQUIRED',
  'ALREADY_OWNED',
  'FIELD_REQUIRED',
  'FIELD_INVALID',
  'SERVER_REQUIRED',
  'SERVER_UNAVAILABLE',
  'GIFT_NOT_ALLOWED',
  'RECIPIENT_UNKNOWN',
  'LOGIN_REQUIRED',
  'NOT_IN_CURRENCY',
  'COUPON_NOT_APPLICABLE',
  'CODE_EXPIRED',
  'CODE_LIMIT_REACHED',
  'CODE_NOT_STARTED',
  'CODE_MIN_AMOUNT',
  'CURRENCY_NOT_SUPPORTED',
  'AMOUNT_BELOW_MINIMUM',
  'AMOUNT_ABOVE_MAXIMUM',
  'GUESTS_NOT_SUPPORTED',
  'PHYSICAL_NOT_SUPPORTED',
  'RECURRING_NOT_SUPPORTED',
  'EXTERNAL_PRICING',
  'MIXED_CREDIT_NOT_SUPPORTED',
  'PROVIDER_INELIGIBLE',
  'BUYER_BLOCKED',
  'TEST_MODE',
  'CODE_NOT_FOUND',
  'CODE_NOT_COMBINABLE',
  'CODE_ATTEMPTS_LOCKED',
  'CREDITS_ONLY',
  'NOT_PAYABLE_WITH_CREDITS',
  'CREDIT_PACK_SEPARATE_ORDER',
  'CREDITS_REDUCED',
  'INSUFFICIENT_CREDITS',
  'INVALID_CREDIT_AMOUNT',
  'MINIMUM_ORDER_AMOUNT_NOT_REACHED',
  'SUBSCRIPTION_MUST_BE_ALONE',
  'SHIPPING_ADDRESS_REQUIRED',
  'SHIPPING_ADDRESS_INVALID',
  'SHIPPING_UNAVAILABLE',
  'SHIPPING_METHOD_REQUIRED',
  // PAYMENT_METHOD_UNAVAILABLE reasons that are not already listed above
  'CREDITS_REQUIRED',
  'CREDITS_DISABLED',
];

/** Theme-only keys (14 §16). */
export const THEME_CODES = ['NETWORK', 'GENERIC', 'SESSION_EXPIRED', 'INVALID_GIFT_CODE'];

/** INVALID_CREDIT_AMOUNT reasons (07 §8.2). JSON cannot hold a leaf and a group under one key, so the reason
 *  keys are `INVALID_CREDIT_AMOUNT_<reason>` (deviation from the dotted spelling of 14 §10.9). */
export const CREDIT_AMOUNT_REASONS = [
  'BELOW_MINIMUM',
  'ABOVE_MAXIMUM',
  'NOT_A_NUMBER',
  'TOPUP_DISABLED',
];

const KEYS = new Set([
  ...HTTP_CODES,
  ...MESSAGE_CODES,
  ...THEME_CODES,
  ...CREDIT_AMOUNT_REASONS.map((reason) => `INVALID_CREDIT_AMOUNT_${reason}`),
]);

/** Every code that has a `theme.errors.<code>` key, sorted. */
export const ERROR_KEYS = [...KEYS].sort();

const GENERIC_KEY = 'theme.errors.GENERIC';

/** `theme.errors.<code>` when the code is known, else `theme.errors.GENERIC`. */
export function messageKey(code) {
  return typeof code === 'string' && KEYS.has(code) ? `theme.errors.${code}` : GENERIC_KEY;
}

/** Key for a `reason` member (PAYMENT_METHOD_UNAVAILABLE, unavailableReason, code reasons) with a fallback code. */
export function reasonKey(reason, fallback = 'GENERIC') {
  return typeof reason === 'string' && KEYS.has(reason)
    ? `theme.errors.${reason}`
    : messageKey(fallback);
}

/** Text key of an INVALID_CREDIT_AMOUNT reason; the generic key for a reason this theme does not know. */
export function creditAmountKey(reason) {
  return CREDIT_AMOUNT_REASONS.includes(reason)
    ? `theme.errors.INVALID_CREDIT_AMOUNT_${reason}`
    : messageKey('INVALID_CREDIT_AMOUNT');
}

// ---- checkout actions (14 §10.9) ----------------------------------------------------------------------------

/** Response members an action may need (copied into `details`). */
const DETAIL_KEYS = [
  'reason',
  'fields',
  'lineErrors',
  'lines',
  'legalTextId',
  'minimum',
  'min',
  'max',
  'retryAfter',
  'balance',
  'maxApplicable',
  'quote',
  'order',
  'orderToken',
  'payment',
  'productId',
];

/**
 * Action table. Members:
 *  kind        STATE | ALERT | FIELD | CODE | PRICE_CHANGED | LEGAL | BILLING | LEAVE | RETRY | RATE_LIMIT |
 *              ORDER_CREATED | RELOAD | NETWORK | GENERIC
 *  state       page state to enter (STATE)
 *  where       summary | shipping | credits | payment | gift (alert / field location)
 *  requote     ask for a fresh quote
 *  dropKey     drop idempotencyKey + bodyHash from the draft (the order of the next submit is a new one)
 *  reloadCart  reload the cart (EMPTY_CART)
 *  dropCode    remove the code from the draft (CODE)
 *  clearMethod / clearCredits  clear the payment method / the credit choice
 *  focus       move the focus to the field or control
 */
const TABLE = {
  EMPTY_CART: { kind: 'STATE', state: 'EMPTY', reloadCart: true, dropKey: true },
  INVALID_CART: { kind: 'ALERT', where: 'summary', requote: true, scroll: true, dropKey: true },
  INVALID_COUPON: { kind: 'CODE', which: 'coupon', dropCode: true, requote: true, dropKey: true },
  INVALID_CREATOR_CODE: {
    kind: 'CODE',
    which: 'creator',
    dropCode: true,
    requote: true,
    dropKey: true,
  },
  INVALID_RECIPIENT: {
    kind: 'FIELD',
    where: 'gift',
    field: 'recipient',
    focus: true,
    dropKey: true,
  },
  MINIMUM_ORDER_AMOUNT_NOT_REACHED: { kind: 'ALERT', where: 'summary', dropKey: true },
  PRICE_CHANGED: {
    kind: 'PRICE_CHANGED',
    where: 'summary',
    replaceQuote: true,
    scroll: true,
    dropKey: true,
  },
  INVALID_CREDIT_AMOUNT: {
    kind: 'FIELD',
    where: 'topup',
    field: 'amount',
    focus: true,
    dropKey: true,
  },
  CREDITS_DISABLED: { kind: 'LEAVE', to: '/store', dropKey: true },
  STORE_BUSY: { kind: 'RETRY', where: 'summary', retryAfterMs: 2000, keepKey: true },
  LEGAL_ACCEPTANCE_REQUIRED: {
    kind: 'LEGAL',
    refetchConfig: true,
    untick: true,
    focus: true,
    dropKey: true,
  },
  BUYER_INFO_REQUIRED: { kind: 'BILLING', openBilling: true, focus: true, dropKey: true },
  SHIPPING_ADDRESS_REQUIRED: {
    kind: 'FIELD',
    where: 'shipping',
    field: 'firstEmpty',
    focus: true,
    dropKey: true,
  },
  SHIPPING_UNAVAILABLE: { kind: 'ALERT', where: 'shipping', requote: true, dropKey: true },
  PAYMENT_METHOD_UNAVAILABLE: {
    kind: 'ALERT',
    where: 'payment',
    clearMethod: true,
    requote: true,
    dropKey: true,
  },
  SUBSCRIPTION_MUST_BE_ALONE: { kind: 'ALERT', where: 'summary', editCart: true, dropKey: true },
  INSUFFICIENT_CREDITS: {
    kind: 'ALERT',
    where: 'credits',
    clearCredits: true,
    requote: true,
    dropKey: true,
  },
  NOT_LOGGED_IN: { kind: 'STATE', state: 'LOGIN_REQUIRED', dropKey: true },
  BUYER_BLOCKED: { kind: 'STATE', state: 'BLOCKED', dropKey: true },
  OUT_OF_STOCK: { kind: 'ALERT', where: 'summary', requote: true, dropKey: true },
  PURCHASE_LIMIT_REACHED: { kind: 'ALERT', where: 'summary', requote: true, dropKey: true },
  COOLDOWN_ACTIVE: { kind: 'ALERT', where: 'summary', requote: true, dropKey: true },
  PRODUCT_REQUIREMENT_NOT_MET: { kind: 'ALERT', where: 'summary', requote: true, dropKey: true },
  IDEMPOTENCY_CONFLICT: {
    kind: 'GENERIC',
    where: 'summary',
    requote: true,
    dropKey: true,
    messageKey: GENERIC_KEY,
  },
  TOO_MANY_REQUESTS: { kind: 'RATE_LIMIT', where: 'summary', disableButton: true, keepKey: true },
  PAYMENT_PROVIDER_ERROR: { kind: 'ORDER_CREATED', storeOrder: true, goto: 'order' },
  INVALID_CSRF_TOKEN: {
    kind: 'RELOAD',
    where: 'summary',
    keepKey: true,
    messageKey: 'theme.errors.SESSION_EXPIRED',
  },
  STORE_DISABLED: { kind: 'STATE', state: 'DISABLED', keepKey: true },
  STORE_UNAVAILABLE: { kind: 'STATE', state: 'DISABLED', keepKey: true },
  NETWORK: {
    kind: 'NETWORK',
    where: 'summary',
    keepKey: true,
    messageKey: 'theme.checkout.network',
  },
};

/** Codes of 10.9 that have an explicit row (the others answer GENERIC). */
export const CHECKOUT_ACTION_CODES = Object.keys(TABLE);

function detailsOf(res) {
  const out = {};

  if (res && typeof res === 'object')
    for (const key of DETAIL_KEYS) if (res[key] !== undefined) out[key] = res[key];

  return out;
}

/**
 * The action of a failed `POST /checkout` (14 §10.9). `code` = the error code (`NETWORK` for a lost
 * answer), `res` = the response (its extras are copied into `details`). Always returns an object: an unknown
 * code is `{ kind: 'GENERIC', dropKey: true }` with the generic text. Every action carries `code`, `kind`,
 * `messageKey` and `details`; `keepKey` / `dropKey` tell what happens to the idempotency key.
 */
export function checkoutAction(code, res = null) {
  const row = typeof code === 'string' && Object.hasOwn(TABLE, code) ? TABLE[code] : null;
  const known = row !== null;
  const base = known
    ? { ...row }
    : { kind: 'GENERIC', where: 'summary', dropKey: true, messageKey: GENERIC_KEY };

  const action = {
    code: known ? code : 'GENERIC',
    ...base,
    messageKey: base.messageKey ?? messageKey(code),
    details: detailsOf(res),
  };

  // PAYMENT_METHOD_UNAVAILABLE says why (TEST_MODE: "this payment method is in test mode")
  if (code === 'PAYMENT_METHOD_UNAVAILABLE')
    action.messageKey = reasonKey(res?.reason, 'PAYMENT_METHOD_UNAVAILABLE');

  // INVALID_COUPON / INVALID_CREATOR_CODE carry the reason the CodeInput shows
  if (code === 'INVALID_COUPON') action.messageKey = reasonKey(res?.reason, 'INVALID_COUPON');
  if (code === 'INVALID_CREATOR_CODE')
    action.messageKey = reasonKey(res?.reason, 'INVALID_CREATOR_CODE');

  if (code === 'INVALID_CREDIT_AMOUNT') action.messageKey = creditAmountKey(res?.reason);

  if (action.keepKey === true) action.dropKey = false;
  else if (action.dropKey !== true) action.dropKey = false;

  return action;
}

/** True when the action ends the checkout form for good (no retry from the form). */
export const isTerminal = (action) =>
  action?.kind === 'STATE' && ['BLOCKED', 'DISABLED'].includes(action.state);
