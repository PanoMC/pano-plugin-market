// Pure model of the order summary (14 §10.5): which totals rows are shown, with the server's numbers only
// (the theme never computes or rounds a total), which quote messages become alerts, the code inputs' state.
import { messageKey, reasonKey } from './errorMap.js';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const positive = (value) => Number(value) > 0;

/**
 * Rows of the totals list in display order: `{ id, labelKey, values?, amount, negative?, strong?, extra? }`.
 * `amount` is the quote's number (null = "—": shipping before a method is chosen). `negative` rows are shown
 * with a leading minus by the component; no row is ever derived by adding or subtracting.
 */
export function summaryRows(quote) {
  if (!isObject(quote)) return [];

  const rows = [
    { id: 'subtotal', labelKey: 'theme.checkout.summary.subtotal', amount: quote.subtotal },
  ];

  if (positive(quote.discountTotal))
    rows.push({
      id: 'discounts',
      labelKey: 'theme.checkout.summary.discounts',
      amount: quote.discountTotal,
      negative: true,
    });

  if (positive(quote.upgradeDiscount))
    rows.push({
      id: 'upgrade',
      labelKey: 'theme.checkout.summary.upgrade',
      amount: quote.upgradeDiscount,
      negative: true,
    });

  if (positive(quote.couponDiscount))
    rows.push({
      id: 'coupon',
      labelKey: 'theme.checkout.summary.coupon',
      values: { code: quote.coupon?.code ?? '' },
      amount: quote.couponDiscount,
      negative: true,
    });

  if (positive(quote.creatorDiscount))
    rows.push({
      id: 'creator',
      labelKey: 'theme.checkout.summary.creator',
      values: { code: quote.creatorCode?.code ?? '' },
      amount: quote.creatorDiscount,
      negative: true,
    });

  if (quote.requiresShipping === true)
    rows.push({
      id: 'shipping',
      labelKey: 'theme.checkout.summary.shipping',
      amount: quote.shippingMethodId ? quote.shippingTotal : null,
    });

  if (positive(quote.paymentFee))
    rows.push({
      id: 'fee',
      labelKey: 'theme.checkout.summary.fee',
      amount: quote.paymentFee,
    });

  if (positive(quote.vatTotal))
    rows.push({
      id: 'vat',
      labelKey: quote.pricesIncludeVat ? 'theme.checkout.vat-included' : 'theme.checkout.vat-added',
      amount: quote.vatTotal,
    });

  rows.push({
    id: 'total',
    labelKey: 'theme.checkout.summary.total',
    amount: quote.total,
    strong: true,
  });

  if (isObject(quote.credits) && positive(quote.credits.applied))
    rows.push({
      id: 'credits',
      labelKey: 'theme.checkout.summary.credits-used',
      amount: quote.credits.appliedValue,
      negative: true,
      extra: { credits: quote.credits.applied, name: quote.credits.name ?? '' },
    });

  if (quote.gatewayAmount !== undefined && quote.gatewayAmount !== quote.total)
    rows.push({
      id: 'to-pay',
      labelKey: 'theme.checkout.summary.to-pay',
      amount: quote.gatewayAmount,
      strong: true,
    });

  return rows;
}

/** The currency the buyer is charged in when it differs from the one shown (`displayCurrency`), else null. */
export function chargedInCurrency(quote) {
  if (!isObject(quote) || typeof quote.displayCurrency !== 'string' || quote.displayCurrency === '')
    return null;

  return quote.displayCurrency !== quote.currency && typeof quote.currency === 'string'
    ? quote.currency
    : null;
}

const LEVEL_CLASS = { error: 'alert-danger', warning: 'alert-warning', info: 'alert-info' };

/** Messages another part of the page already shows (a field, the picker, the page state). */
const SHOWN_ELSEWHERE = [
  'RECIPIENT_UNKNOWN',
  'SHIPPING_ADDRESS_REQUIRED',
  'SHIPPING_ADDRESS_INVALID',
  'SHIPPING_METHOD_REQUIRED',
  'SHIPPING_UNAVAILABLE',
  'LOGIN_REQUIRED',
  'EXTERNAL_PRICING',
  'MIXED_CREDIT_NOT_SUPPORTED',
];

/**
 * Quote messages that are not tied to a line, as alerts: `{ code, level, cls, messageKey, values }`. `cls` is the
 * Bootstrap alert class (error -> alert-danger). Messages with a `lineKey`, those the page shows elsewhere, the
 * blocked-buyer error (page state) and the coupon / creator-code reasons (shown at the CodeInput) are left out.
 */
export function summaryAlerts(quote) {
  if (!Array.isArray(quote?.messages)) return [];

  const codeReasons = new Set(
    [quote.coupon?.reason, quote.creatorCode?.reason].filter(
      (reason) => typeof reason === 'string',
    ),
  );
  const out = [];

  for (const message of quote.messages) {
    if (!isObject(message) || typeof message.code !== 'string') continue;
    if (message.lineKey) continue;
    if (SHOWN_ELSEWHERE.includes(message.code) || codeReasons.has(message.code)) continue;
    if (message.code === 'BUYER_BLOCKED' && message.level === 'error') continue;

    const values = {};
    for (const key of ['minimum', 'retryAfter', 'min', 'max', 'balance', 'maxApplicable'])
      if (typeof message[key] === 'number') values[key] = message[key];

    if (
      message.code === 'MINIMUM_ORDER_AMOUNT_NOT_REACHED' &&
      values.minimum === undefined &&
      typeof quote.minimumOrderAmount === 'number'
    )
      values.minimum = quote.minimumOrderAmount;

    out.push({
      code: message.code,
      level: message.level,
      cls: LEVEL_CLASS[message.level] ?? 'alert-info',
      messageKey: messageKey(message.code),
      values,
    });
  }

  return out;
}

/** Error codes of a line, as `{ code, messageKey }` (an unknown code reads as the generic text). */
export function lineErrors(line) {
  if (!Array.isArray(line?.errors)) return [];

  return line.errors
    .filter((code) => typeof code === 'string')
    .map((code) => ({ code, messageKey: messageKey(code) }));
}

/** Lines of the summary: the bundle children stay under their parent and are not listed again. */
export function summaryLines(quote) {
  if (!Array.isArray(quote?.lines)) return [];

  return quote.lines.filter((line) => isObject(line) && line.kind !== 'BUNDLE_CHILD');
}

// ---- code inputs ----------------------------------------------------------------------------------------------

/**
 * State of a code input (`result` = `quote.coupon` / `quote.creatorCode`): `{ status, reason?, messageKey?,
 * retryAfter? }` with status APPLIED (valid), INVALID (valid === false), LOCKED (attempts locked) or IDLE.
 */
export function codeState(result, fallbackCode, applied = '') {
  if (!isObject(result)) return { status: 'IDLE' };

  if (result.reason === 'CODE_ATTEMPTS_LOCKED')
    return {
      status: 'LOCKED',
      reason: result.reason,
      messageKey: messageKey('CODE_ATTEMPTS_LOCKED'),
      retryAfter: Number(result.retryAfter) > 0 ? Number(result.retryAfter) : 60,
    };

  if (result.valid === true && applied !== '')
    return { status: 'APPLIED', code: result.code ?? applied };

  if (result.valid === false)
    return {
      status: 'INVALID',
      reason: result.reason ?? null,
      messageKey: reasonKey(result.reason, fallbackCode),
    };

  return { status: 'IDLE' };
}

/** Both code inputs are hidden in top-up mode and for a method with `pricing === 'EXTERNAL'` (14 §10.5). */
export function codesHidden({ topup = null, method = null }) {
  return topup !== null || method?.pricing === 'EXTERNAL';
}

/**
 * The next UI state of a code input after a quote: `{ ui, drop }`. An invalid or locked code is dropped from the
 * draft (`drop`) so the body no longer carries it, while the input keeps showing why (the state outlives the
 * code); a quote that says nothing about the code keeps a shown error until the buyer types again.
 * `current` = the state shown now, `result` = quote.coupon / quote.creatorCode, `applied` = the draft's code,
 * `nowMs` = the clock (the lock ends `retryAfter` seconds later).
 */
export function nextCodeState({ current, result, applied = '', fallbackCode, nowMs = 0 }) {
  const state = codeState(result, fallbackCode, applied);

  if (state.status === 'INVALID') return { ui: state, drop: true };
  if (state.status === 'LOCKED')
    return { ui: { ...state, until: nowMs + state.retryAfter * 1000 }, drop: true };
  if (state.status === 'APPLIED') return { ui: state, drop: false };

  return { ui: applied !== '' ? { status: 'IDLE' } : (current ?? { status: 'IDLE' }), drop: false };
}
