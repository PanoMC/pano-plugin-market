// Pure model of the credits section, the payment method picker and the legal checkbox (14 §10.5).
// The theme never computes a total: every amount shown comes from the quote; this module only decides what to show,
// which draft members to clear and what a control may send. No SDK, no DOM: unit-tested.
import { messageKey } from './errorMap.js';

/** Name of the one radio group shared by the credits radio and the payment methods (14 §10.5). */
export const PAY_GROUP = 'market-pay';

/** DOM id of the legal checkbox (the submit focuses it). */
export const LEGAL_CHECK_ID = 'market-checkout-legal';

/** Placeholder swapped for the legal title when the label is split around the title button. */
export const TITLE_SENTINEL = '';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

// ---- values from the server that end up in markup ----------------------------------------------------------

/** `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa` (an admin-chosen provider colour), else null. */
export function safeColor(value) {
  return typeof value === 'string' && /^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(value)
    ? value
    : null;
}

export const DEFAULT_ICON = 'fa-solid fa-credit-card';

/** A Font Awesome class list (`fa-solid fa-credit-card`); anything else falls back to the card icon. */
export function safeIcon(icon) {
  if (typeof icon !== 'string') return DEFAULT_ICON;

  const tokens = icon.trim().split(/\s+/).filter(Boolean);
  const valid =
    tokens.length > 0 &&
    tokens.length <= 6 &&
    tokens.every((token) => /^fa(?:-[a-z0-9]+)*$/.test(token) && token.length <= 40) &&
    tokens.some((token) => token !== 'fa-solid' && token !== 'fa-regular' && token !== 'fa-brands');

  return valid ? tokens.join(' ') : DEFAULT_ICON;
}

/** The URL when it is an absolute `http:` / `https:` URL, else null. */
export function safeHttpUrl(value) {
  if (typeof value !== 'string' || value.length > 2048 || /[\u0000- \u007f]/.test(value))
    return null;
  if (!/^https?:\/\//i.test(value)) return null;

  try {
    const url = new URL(value);

    return url.protocol === 'http:' || url.protocol === 'https:' ? value : null;
  } catch (e) {
    return null;
  }
}

/** `{ url, relative }` of a logo: http(s) as it is, a root-relative path (`/api/...`, not `//x`) to be prefixed. */
export function logoSource(value) {
  if (typeof value !== 'string' || value === '' || value.length > 2048) return null;
  if (/[\u0000- \u007f\\]/.test(value)) return null;

  const absolute = safeHttpUrl(value);
  if (absolute) return { url: absolute, relative: false };
  if (value[0] === '/' && value[1] !== '/') return { url: value, relative: true };

  return null;
}

/** Notice links of a method: `{label, url}` with an http(s) URL and a label, in server order. */
export function noticeLinks(method) {
  if (!Array.isArray(method?.notices)) return [];

  const out = [];

  for (const notice of method.notices) {
    const url = safeHttpUrl(notice?.url);
    const label = typeof notice?.label === 'string' ? notice.label.trim() : '';
    if (url && label) out.push({ label, url });
  }

  return out;
}

// ---- payment methods ----------------------------------------------------------------------------------------

export const isExternalPricing = (method) =>
  method?.pricing === 'EXTERNAL' || method?.pricing === 'EXTERNAL_TAX';

/** `theme.errors.<reason>`; an unknown reason answers PAYMENT_METHOD_UNAVAILABLE (14 §10.5). */
export function unavailableKey(reason) {
  const key = messageKey(reason);

  return key === 'theme.errors.GENERIC' ? messageKey('PAYMENT_METHOD_UNAVAILABLE') : key;
}

/** The method with `id` out of `quote.paymentMethods`, or null. */
export function selectedMethod(quote, id) {
  if (id === null || id === undefined || !Array.isArray(quote?.paymentMethods)) return null;

  return quote.paymentMethods.find((method) => method?.id === id) ?? null;
}

/**
 * What the picker renders: `{ mode, methods }` with mode NONE_NEEDED (`gatewayAmount === 0`),
 * NO_METHOD (nothing available) or LIST. Methods keep the server order.
 */
export function pickerView(quote) {
  if (!quote) return { mode: 'LIST', methods: [] };

  const methods = Array.isArray(quote.paymentMethods) ? quote.paymentMethods.filter(isObject) : [];

  if (Number(quote.gatewayAmount) === 0) return { mode: 'NONE_NEEDED', methods };
  if (!methods.some((method) => method.available !== false)) return { mode: 'NO_METHOD', methods };

  return { mode: 'LIST', methods };
}

// ---- credits ------------------------------------------------------------------------------------------------

/** `{ enabled, payable, insufficient }`: the credits radio (pay the whole order in credits). */
export function creditsRadio(credits) {
  const enabled = isObject(credits) && credits.enabled === true;
  const payable = enabled && credits.payableInCredits === true;

  return {
    enabled,
    payable,
    insufficient: payable && Number(credits.balance) < Number(credits.creditTotal),
  };
}

/**
 * Are the mixed-payment controls shown? Only for `config.mixedCredit`, a buyer who can apply something, not while
 * the whole order is paid in credits, never for a method with `pricing !== 'MARKET'` and not for a method whose
 * option says it cannot be combined with credits.
 */
export function mixedControlsVisible({ config, credits, payWithCredits = false, method = null }) {
  if (config?.mixedCredit !== true) return false;
  if (!isObject(credits) || credits.enabled !== true) return false;
  if (!(Number(credits.maxApplicable) > 0)) return false;
  if (payWithCredits === true) return false;
  if (method && method.pricing !== undefined && method.pricing !== 'MARKET') return false;
  if (method && method.unavailableReason === 'MIXED_CREDIT_NOT_SUPPORTED') return false;

  return true;
}

/** True when part of the order is paid in credits (the buyer ticked the box: never the default). */
export const mixedActive = (draft) => draft?.useCredits !== null && draft?.useCredits !== undefined;

/**
 * Reads the number input: `{ ok: true, value }` (two decimals, `>= 0`), `{ ok: false }` for text that is not a
 * non-negative number. Values above `max` are kept: the server answers CREDITS_REDUCED, the buyer sees the result.
 */
export function parseCreditAmount(text) {
  const trimmed = typeof text === 'string' ? text.trim().replace(',', '.') : String(text ?? '');
  if (!/^\d{1,12}(\.\d{0,4})?$/.test(trimmed)) return { ok: false };

  const value = Math.round(Number(trimmed) * 100) / 100;

  return Number.isFinite(value) && value >= 0 ? { ok: true, value } : { ok: false };
}

/**
 * Draft members to change after a quote so the credit choice stays valid (never turns credits on): credits
 * switched off or not available clear both members; paying in credits clears `useCredits`; a method with
 * `pricing !== 'MARKET'` clears `useCredits`.
 */
export function creditsPatchAfterQuote({ draft, quote, config }) {
  const patch = {};
  const credits = quote?.credits;
  const enabled = isObject(credits) && credits.enabled === true;
  const method = selectedMethod(quote, draft?.paymentMethodId ?? null);

  if (draft?.payWithCredits === true && !(enabled && credits.payableInCredits === true))
    patch.payWithCredits = false;

  const paying = patch.payWithCredits === undefined ? draft?.payWithCredits === true : false;

  if (mixedActive(draft)) {
    const allowed = mixedControlsVisible({ config, credits, payWithCredits: paying, method });
    if (!allowed) patch.useCredits = null;
  }

  return patch;
}

/**
 * Draft patch applied to a restored draft: credits are never pre-applied, so a credit choice stored by an earlier
 * visit (or before a reload) is dropped and the buyer confirms it again. `{}` when the draft has none.
 */
export function restoredCreditsPatch(draft) {
  return draft?.payWithCredits === true || mixedActive(draft)
    ? { payWithCredits: false, useCredits: null }
    : {};
}

/** Draft patch of choosing a payment method (a method and credit payment are exclusive). */
export function selectMethodPatch(draft, id, method = null) {
  const patch = { paymentMethodId: id, payWithCredits: false };

  if (method && method.pricing !== undefined && method.pricing !== 'MARKET' && mixedActive(draft))
    patch.useCredits = null;

  return patch;
}

/** Draft patch of choosing "pay with credits" (clears the method and the mixed amount). */
export const selectCreditsPatch = () => ({
  payWithCredits: true,
  paymentMethodId: null,
  useCredits: null,
});

// ---- place order ----------------------------------------------------------------------------------------------

/**
 * `{ disabled, mode, amount }` of the place-order button. `mode` PAY / COMPLETE (`gatewayAmount === 0`); `amount`
 * is the server's `gatewayAmount` (never computed here). Disabled while QUOTING / SUBMITTING / not READY, without
 * a quote, when `!quote.canCheckout`, or without a method while something is left to pay.
 */
export function placeOrderState({ pageState, quote, draft }) {
  const amount = Number(quote?.gatewayAmount);
  const complete = Number.isFinite(amount) && amount === 0;
  const needsMethod = !complete && draft?.payWithCredits !== true;

  const disabled =
    pageState !== 'READY' ||
    !quote ||
    quote.canCheckout !== true ||
    (needsMethod && !draft?.paymentMethodId);

  return {
    disabled,
    mode: complete ? 'COMPLETE' : 'PAY',
    amount: quote?.gatewayAmount ?? null,
  };
}

// ---- legal ----------------------------------------------------------------------------------------------------

/**
 * Splits the translated label ("I have read and accept the {title}", the title swapped for TITLE_SENTINEL) around
 * the title: `{ before, after }` (the title button goes between). Without the sentinel the text is `before`.
 */
export function splitLegalLabel(text) {
  const value = typeof text === 'string' ? text : '';
  const at = value.indexOf(TITLE_SENTINEL);

  return at < 0
    ? { before: value, after: '' }
    : { before: value.slice(0, at), after: value.slice(at + TITLE_SENTINEL.length) };
}

/** True when the text the quote was priced against differs from the config's (a new version was published). */
export function legalChanged(config, quote) {
  const wanted = quote?.legal?.id;
  const shown = config?.legal?.id;

  return wanted !== undefined && wanted !== null && shown !== undefined && wanted !== shown;
}
