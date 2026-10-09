// Pure model of the order page payment panel (14 §11.4): which start UI shows, the generic embedded form, the
// script loader, the bank transfer notice and the "pay another way" request. No SDK, no DOM (the script loader
// takes its `document`): unit-tested. Nothing here trusts a URL or a script the server sent: scripts and iframes
// are https only, a redirect / attempt page passes the checks of paymentStart.js.
import {
  billingRequirements,
  effectiveBillingInfo,
  emptyAddress,
  firstInvalidId,
  validateBilling,
} from './checkoutModel.js';
import { messageKey } from './errorMap.js';
import { mixedControlsVisible, unavailableKey } from './paymentModel.js';
import {
  IN_PAGE_KINDS,
  afterCheckout,
  isAttemptPageUrl,
  isSafeExternalUrl,
} from './paymentStart.js';
import { compilePattern } from './validation.js';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

const hasControlChars = (value) => {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code <= 0x20 || code === 0x7f) return true;
  }

  return false;
};

// ---- urls and scripts -------------------------------------------------------------------------------------

/** True for an absolute `https:` URL with a host, without whitespace or control characters. */
export function isHttpsUrl(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > 2048) return false;
  if (hasControlChars(value) || !/^https:\/\//i.test(value)) return false;

  try {
    const url = new URL(value);

    return url.protocol === 'https:' && url.hostname !== '' && url.username === '';
  } catch (e) {
    return false;
  }
}

export const MAX_SCRIPTS = 8;

/**
 * The scripts a start asks for: `{ urls, valid }`. Every entry must be an https URL; one that is not (or more
 * than MAX_SCRIPTS) makes the whole list invalid, because a half-loaded gateway UI is worse than none. Duplicates
 * collapse to the first occurrence (order kept).
 */
export function scriptsPlan(list) {
  if (list === undefined || list === null) return { urls: [], valid: true };
  if (!Array.isArray(list)) return { urls: [], valid: false };

  const urls = [];
  let valid = list.length <= MAX_SCRIPTS;

  for (const entry of list) {
    if (!isHttpsUrl(entry)) {
      valid = false;
      continue;
    }

    if (!urls.includes(entry)) urls.push(entry);
  }

  return { urls: valid ? urls : [], valid };
}

/**
 * Appends external scripts to `document.head` once each (de-duplicated by URL), one after the other so their order
 * is kept. `load(url)` resolves when the script loaded and rejects when it failed (the element is removed so a
 * later try loads it again). `doc` = a Document (injected so tests need no DOM).
 */
export function createScriptLoader(doc) {
  const pending = new Map();

  function load(url) {
    if (!isHttpsUrl(url)) return Promise.reject(new Error('SCRIPT_URL'));
    if (pending.has(url)) return pending.get(url);

    const promise = new Promise((resolve, reject) => {
      const script = doc.createElement('script');

      script.src = url;
      script.async = true;
      script.dataset.panoMarketPayment = '1';
      script.onload = () => resolve(url);
      script.onerror = () => {
        script.remove?.();
        pending.delete(url);
        reject(new Error('SCRIPT_LOAD'));
      };

      doc.head.appendChild(script);
    });

    pending.set(url, promise);

    return promise;
  }

  async function loadAll(urls) {
    for (const url of urls) await load(url);
  }

  return { load, loadAll };
}

let sharedLoader = null;

/** Loads `urls` into the real document, once per URL for the whole page (browser only). */
export function loadScripts(urls) {
  if (typeof document === 'undefined') return Promise.reject(new Error('NO_DOCUMENT'));
  sharedLoader ??= createScriptLoader(document);

  return sharedLoader.loadAll(urls);
}

// ---- iframe -----------------------------------------------------------------------------------------------

export const IFRAME_DEFAULT_HEIGHT = 640;

/** `height` attribute: an integer between 200 and 2400, else 640. */
export function iframeHeight(value) {
  const n = Number(value);

  return Number.isInteger(n) && n >= 200 && n <= 2400 ? n : IFRAME_DEFAULT_HEIGHT;
}

/** `allow` attribute (a permissions policy such as "payment"): plain tokens only, else undefined (no attribute). */
export function iframeAllow(value) {
  if (typeof value !== 'string') return undefined;

  const text = value.trim();

  return text !== '' && text.length <= 256 && /^[A-Za-z0-9 ;:'*.\-/]+$/.test(text)
    ? text
    : undefined;
}

/** Options of `window.iFrameResize` for a PayTR-like resizer, or null when the start asks for none. */
export function resizerOptions(iframe) {
  if (!isObject(iframe) || iframe.resizer !== 'IFRAME_RESIZER' || !isHttpsUrl(iframe.url))
    return null;

  try {
    return { checkOrigin: [new URL(iframe.url).origin] };
  } catch (e) {
    return null;
  }
}

// ---- which start UI shows ---------------------------------------------------------------------------------

export const COMPONENT_VIEW_PREFIX = 'market:checkout:payment:';

/** A plugin component is only looked up under the id namespace of 02 §6 (never an arbitrary slot such as a navbar). */
export const isComponentId = (id) =>
  typeof id === 'string' &&
  id.startsWith(COMPONENT_VIEW_PREFIX) &&
  /^[a-z0-9][a-z0-9_.-]{0,63}$/i.test(id.slice(COMPONENT_VIEW_PREFIX.length));

/** The generic fields of an embedded start (objects with a string key), in server order. */
export function embeddedFields(start) {
  const fields = start?.embedded?.fields;

  return Array.isArray(fields)
    ? fields.filter((field) => isObject(field) && typeof field.key === 'string' && field.key !== '')
    : [];
}

/** What an embedded start shows when no plugin component can be used: the generic form or the missing notice. */
export const embeddedFallback = (start) =>
  embeddedFields(start).length > 0 ? 'GENERIC' : 'MISSING';

/**
 * `LINK` (a button: REDIRECT / FORM_POST / HTML with a checked URL), `IFRAME`, `COMPONENT` (plugin UI, falls back
 * to `embeddedFallback`), `GENERIC`, `MISSING`, `INSTRUCTIONS`, `UI_ERROR` (an iframe URL that is not https) or
 * `NONE` (COMPLETED, an unknown kind, an unsafe link).
 */
export function startMode(start, context = {}) {
  if (!isObject(start)) return 'NONE';

  switch (start.kind) {
    case 'REDIRECT':
      return isSafeExternalUrl(start.url) ? 'LINK' : 'NONE';
    case 'FORM_POST':
    case 'HTML':
      return isAttemptPageUrl(start.url, context) ? 'LINK' : 'NONE';
    case 'IFRAME':
      return isHttpsUrl(start.iframe?.url) ? 'IFRAME' : 'UI_ERROR';
    case 'EMBEDDED':
      return isComponentId(start.embedded?.component) ? 'COMPONENT' : embeddedFallback(start);
    case 'INSTRUCTIONS':
      return isObject(start.instructions) ? 'INSTRUCTIONS' : 'NONE';
    default:
      return 'NONE';
  }
}

/** The start shown: a start returned by `continue` / `pay` while the order still carries the old one, else the order's. */
export function effectiveStart(order, override = null) {
  const own = order?.payment?.start ?? null;

  return override && override.base === own ? override.value : own;
}

/**
 * Identity of a start by content: an order re-fetch hands out a new object for the same start, which must not
 * rebuild the iframe or a half-filled form. '' for no start.
 */
export function startSignature(start) {
  if (!isObject(start)) return '';

  try {
    return JSON.stringify(start);
  } catch (e) {
    return '';
  }
}

/** True while a start can be used: the attempt is `PENDING` and the start has not expired (`expiresAt` epoch ms). */
export function startUsable(order, start, now = 0) {
  if (!isObject(start) || order?.payment?.status !== 'PENDING') return false;

  const end = Number(start.expiresAt);

  return !(
    start.expiresAt !== null &&
    start.expiresAt !== undefined &&
    Number.isFinite(end) &&
    end > 0 &&
    end <= now
  );
}

/** `order.canRetryPayment` with something to choose from. */
export const canRetry = (order) =>
  order?.canRetryPayment === true &&
  Array.isArray(order?.paymentMethods) &&
  order.paymentMethods.some((method) => isObject(method) && method.available !== false);

/**
 * `{ show, readonly, mode, start, canRetry, methodsOpen }` of the panel. AWAITING_PAYMENT (`view.panels.payment`)
 * renders the start UI and the "pay another way" part; PROCESSING (`view.panels.instructions`) renders the
 * instructions read-only. "Pay another way" starts expanded when there is no usable start (14 §11.4).
 */
export function panelModel({ order, view, now = 0, override = null, context = {} } = {}) {
  const hidden = {
    show: false,
    readonly: false,
    mode: 'NONE',
    start: null,
    canRetry: false,
    methodsOpen: false,
  };

  if (!isObject(order) || !isObject(view) || view.limited === true) return hidden;

  const start = effectiveStart(order, override);

  if (view.panels?.payment === true) {
    const usable = startUsable(order, start, now);
    const mode = usable ? startMode(start, context) : 'NONE';
    const retry = canRetry(order);
    const noUi = mode === 'NONE' || mode === 'UI_ERROR' || mode === 'MISSING';

    return {
      show: mode !== 'NONE' || retry,
      readonly: false,
      mode,
      start: usable ? start : null,
      canRetry: retry,
      methodsOpen: retry && noUi,
    };
  }

  if (view.panels?.instructions === true) {
    const show = start?.kind === 'INSTRUCTIONS' && isObject(start.instructions);

    return {
      ...hidden,
      show,
      readonly: true,
      mode: show ? 'INSTRUCTIONS' : 'NONE',
      start: show ? start : null,
    };
  }

  return hidden;
}

// ---- plugin component lookup ------------------------------------------------------------------------------

/** Items of a view store (`pano.ui.view.get`) or of a plain array; [] when it cannot be read. */
export function viewItems(source) {
  try {
    if (Array.isArray(source)) return source;
    if (typeof source?.subscribe === 'function') {
      // The store contract (subscribe calls back synchronously) read by hand: this file is reached from a
      // controller (profileModel), and controllers import no framework code.
      let items;
      const stop = source.subscribe((value) => {
        items = value;
      });

      if (typeof stop === 'function') stop();
      else if (typeof stop?.unsubscribe === 'function') stop.unsubscribe();

      return Array.isArray(items) ? items : [];
    }
  } catch (e) {
    // a broken registry means "no component"
  }

  return [];
}

/** First registered item that carries a component, or null. */
export const firstComponentItem = (source) =>
  viewItems(source).find((item) => isObject(item) && item.component) ?? null;

/**
 * Resolves the thunk of an item to a Svelte component (module object, `default` export or the component itself).
 * A thunk is a function that takes no argument (or carries the `_importer` mark of `viewComponent()`); a compiled
 * Svelte component always takes the anchor argument. null when nothing usable comes out (or the thunk throws).
 */
export async function resolveComponent(item) {
  try {
    let value = item?.component;

    if (typeof value === 'function' && (value._importer !== undefined || value.length === 0))
      value = await value();

    const component = value?.default ?? value;

    return typeof component === 'function' ? component : null;
  } catch (e) {
    return null;
  }
}

// ---- generic form (14 §11.4 `EMBEDDED` with `fields`) -----------------------------------------------------

export const FIELD_TYPES = [
  'TEXT',
  'URL',
  'NUMBER',
  'PASSWORD',
  'TEXTAREA',
  'SELECT',
  'SWITCH',
  'NOTICE',
  'READONLY',
];

export const TEXT_MAX = 512;
export const TEXTAREA_MAX = 4000;

const isInput = (field) => field.type !== 'NOTICE' && field.type !== 'READONLY';

/** `visibleWhen {field, anyOf}`; the referenced field must itself be visible (a cycle counts as visible). */
export function isVisible(field, values, fields, seen = new Set()) {
  const rule = field?.visibleWhen;

  if (!isObject(rule) || typeof rule.field !== 'string') return true;
  if (seen.has(field.key)) return true;

  const next = new Set(seen).add(field.key);
  const anyOf = Array.isArray(rule.anyOf) ? rule.anyOf.map(String) : [];

  if (!anyOf.includes(String(values?.[rule.field]))) return false;

  const parent = fields.find((candidate) => candidate.key === rule.field);

  return parent ? isVisible(parent, values, fields, next) : true;
}

/** Starting values of the inputs: `default`, `false` for a switch, '' otherwise. */
export function initialValues(fields) {
  const out = {};

  for (const field of fields) {
    if (!isInput(field)) continue;

    const given = field.default;

    if (field.type === 'SWITCH') out[field.key] = given === true || given === 'true';
    else out[field.key] = given === null || given === undefined ? '' : String(given);
  }

  return out;
}

/** Text of a READONLY field: the server's resolved value. */
export function readonlyValue(field) {
  const value = field?.readonly?.value ?? field?.value ?? field?.default;

  return value === null || value === undefined ? '' : String(value);
}

const empty = (value) => value === undefined || value === null || String(value).trim() === '';

function isHttpUrl(value) {
  try {
    const url = new URL(String(value));

    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch (e) {
    return false;
  }
}

/** `FIELD_REQUIRED` / `FIELD_INVALID` / null for one input (a hidden field is never validated). */
export function validateInput(field, value) {
  if (!isInput(field)) return null;
  if (field.type === 'SWITCH') return null;

  if (empty(value)) return field.required === true ? 'FIELD_REQUIRED' : null;

  const text = String(value).trim();

  if (field.type === 'NUMBER') {
    if (!/^-?\d{1,15}$/.test(text)) return 'FIELD_INVALID';

    const n = Number(text);
    const min = field.min === null || field.min === undefined ? null : Number(field.min);
    const max = field.max === null || field.max === undefined ? null : Number(field.max);

    if (
      (min !== null && Number.isFinite(min) && n < min) ||
      (max !== null && Number.isFinite(max) && n > max)
    )
      return 'FIELD_INVALID';

    return null;
  }

  if (field.type === 'SELECT') {
    const options = Array.isArray(field.options) ? field.options : [];

    return options.some((option) => String(option?.value ?? option) === text)
      ? null
      : 'FIELD_INVALID';
  }

  if (field.type === 'URL' && !isHttpUrl(text)) return 'FIELD_INVALID';

  if (text.length > (field.type === 'TEXTAREA' ? TEXTAREA_MAX : TEXT_MAX)) return 'FIELD_INVALID';

  const re = compilePattern(field.pattern);

  return re && !re.test(text) ? 'FIELD_INVALID' : null;
}

/** `{ <key>: code }` for every visible input that fails. */
export function validateForm(fields, values) {
  const errors = {};

  for (const field of fields) {
    if (!isInput(field) || !isVisible(field, values, fields)) continue;

    const code = validateInput(field, values?.[field.key]);
    if (code) errors[field.key] = code;
  }

  return errors;
}

/**
 * The `values` of `POST …/payment/continue`: every visible input, trimmed; a number as a number; a switch as a
 * boolean; an empty optional input is left out. Hidden inputs are never sent.
 */
export function buildValues(fields, values) {
  const out = {};

  for (const field of fields) {
    if (!isInput(field) || !isVisible(field, values, fields)) continue;

    const value = values?.[field.key];

    if (field.type === 'SWITCH') out[field.key] = value === true;
    else if (!empty(value))
      out[field.key] =
        field.type === 'NUMBER' ? Number(String(value).trim()) : String(value).trim();
  }

  return out;
}

/** DOM id of a generic form control. */
export const controlId = (key) => `market-order-pay-${String(key).replace(/[^A-Za-z0-9_-]/g, '_')}`;

// ---- answers of continue / pay / notify -------------------------------------------------------------------

export const MAX_WAIT_SECONDS = 3600;

/** `retryAfter` seconds of a TOO_MANY_REQUESTS answer, 1..3600, 60 when missing. */
export function waitSeconds(value) {
  const n = Number(value);

  return Number.isFinite(n) && n > 0 ? Math.min(MAX_WAIT_SECONDS, Math.ceil(n)) : 60;
}

/**
 * `POST …/payment/continue` failed: `{ alertKey, seconds?, refetch, openMethods }`. The gateway's own text is never
 * shown (`message` of the answer is ignored).
 */
export function continueFailure(res) {
  const code = res?.code ?? 'NETWORK';

  if (code === 'BAD_REQUEST')
    return { alertKey: 'theme.order.payment-form-invalid', refetch: false, openMethods: false };
  if (code === 'PAYMENT_PROVIDER_ERROR')
    return { alertKey: messageKey(code), refetch: true, openMethods: true };
  if (code === 'ORDER_NOT_PAYABLE')
    return { alertKey: messageKey(code), refetch: true, openMethods: false };
  if (code === 'TOO_MANY_REQUESTS')
    return {
      alertKey: messageKey(code),
      seconds: waitSeconds(res?.retryAfter),
      refetch: false,
      openMethods: false,
    };

  return { alertKey: messageKey(code), refetch: false, openMethods: false };
}

/**
 * What the page does with the `PaymentStart` that `continue` / `pay` answered (14 §10.8 for the kinds that leave
 * the page): `ASSIGN` (navigate to a checked url), `SHOW` (an in-page start: render it now, the order is re-fetched
 * too) or `REFETCH` (COMPLETED, an unknown kind or an unsafe link: the order page shows the state).
 */
export function nextStep(payment, order, context = {}) {
  const step = afterCheckout(payment, order, context);

  if (step.type === 'ASSIGN') return step;
  if (['IFRAME', 'EMBEDDED', 'INSTRUCTIONS'].includes(payment?.kind))
    return { type: 'SHOW', start: payment };

  return { type: 'REFETCH' };
}

export { IN_PAGE_KINDS };

// ---- pay another way ----------------------------------------------------------------------------------------

/** Number of credits of a mixed payment, or null: `'MAX'` is the most that can be applied. */
export function creditsAmount(useCredits, credits) {
  if (useCredits === 'MAX') {
    const max = Number(credits?.maxApplicable);

    return Number.isFinite(max) && max > 0 ? max : null;
  }

  return typeof useCredits === 'number' && Number.isFinite(useCredits) && useCredits >= 0
    ? useCredits
    : null;
}

/** Credits the order already pays (`totals.creditAmount`), 0 when none. */
export function existingCredits(order) {
  const amount = Number(order?.totals?.creditAmount);

  return Number.isFinite(amount) && amount > 0 ? amount : 0;
}

/**
 * The credits object the shared credits control renders for an existing order (no "pay all in credits" radio).
 * An existing credit part is shown as applied and the control can always offer at least that much, so the buyer
 * can see it and untick it.
 */
export function creditsForOrder(order) {
  const credits = order?.credits;

  if (!isObject(credits) || credits.enabled !== true) return null;

  const had = existingCredits(order);
  const value = Number(order?.totals?.creditValue);
  const max = Number(credits.maxApplicable);

  return {
    ...credits,
    payableInCredits: false,
    creditTotal: 0,
    maxApplicable: had > 0 ? Math.max(Number.isFinite(max) ? max : 0, had) : credits.maxApplicable,
    applied: had,
    appliedValue: had > 0 && Number.isFinite(value) ? value : 0,
  };
}

/** The `useCredits` the panel may send: the buyer's choice only while the credit control is on screen, else null. */
export function payCredits({ useCredits = null, credits = null, method = null } = {}) {
  const visible = mixedControlsVisible({ config: { mixedCredit: true }, credits, method });

  return visible ? useCredits : null;
}

/** A quote-shaped object for the method picker: the order's methods; a retry always has something left to pay. */
export function pickerQuote(order) {
  return {
    paymentMethods: Array.isArray(order?.paymentMethods) ? order.paymentMethods : [],
    gatewayAmount: Number(order?.totals?.gatewayAmount) > 0 ? order.totals.gatewayAmount : 1,
  };
}

/** The method to preselect: the order's current one when it is still available, else none. */
export function defaultMethodId(order) {
  const current = order?.payment?.methodId;
  const methods = Array.isArray(order?.paymentMethods) ? order.paymentMethods : [];

  return methods.some((method) => method?.id === current && method.available !== false)
    ? current
    : null;
}

/**
 * Body of `POST …/pay`: `{ paymentMethodId, useCredits?, billingInfo? }`. `useCredits` only as a number. An omitted
 * `useCredits` keeps the order's existing credit part (04 §3), so when the order had one (`hadCredits`) and the
 * buyer sends no amount, `0` drops it.
 */
export function payBody({
  methodId,
  useCredits = null,
  credits = null,
  billingInfo = null,
  hadCredits = false,
} = {}) {
  const body = { paymentMethodId: methodId };
  const amount = creditsAmount(useCredits, credits) ?? (hadCredits ? 0 : null);

  if (amount !== null) body.useCredits = amount;
  if (isObject(billingInfo) && Object.keys(billingInfo).length > 0) body.billingInfo = billingInfo;

  return body;
}

/** True when the "Pay" button may be pressed. */
export function canPay({
  order,
  methodId,
  busy = false,
  waiting = false,
  needsBilling = false,
} = {}) {
  if (busy || waiting || !methodId) return false;

  const method = (order?.paymentMethods ?? []).find((candidate) => candidate?.id === methodId);

  return method !== undefined && method.available !== false && !needsBilling;
}

/**
 * `POST …/pay` failed: `{ alertKey, seconds?, refetch, clearMethod, billingFields?, clearCredits }`. Never the
 * gateway's text. BUYER_INFO_REQUIRED carries the `fields` the billing part is limited to.
 */
export function payFailure(res) {
  const code = res?.code ?? 'NETWORK';
  const base = { refetch: false, clearMethod: false, clearCredits: false };

  if (code === 'ORDER_NOT_PAYABLE') return { ...base, alertKey: messageKey(code), refetch: true };
  if (code === 'PAYMENT_METHOD_UNAVAILABLE')
    return { ...base, alertKey: unavailableKey(res?.reason), refetch: true, clearMethod: true };
  if (code === 'BUYER_INFO_REQUIRED')
    return {
      ...base,
      alertKey: messageKey(code),
      billingFields: Array.isArray(res?.fields)
        ? res.fields.filter((f) => typeof f === 'string')
        : [],
    };
  if (code === 'PAYMENT_PROVIDER_ERROR')
    return { ...base, alertKey: messageKey(code), refetch: true };
  if (code === 'INSUFFICIENT_CREDITS')
    return { ...base, alertKey: messageKey(code), clearCredits: true, refetch: true };
  if (code === 'TOO_MANY_REQUESTS')
    return { ...base, alertKey: messageKey(code), seconds: waitSeconds(res?.retryAfter) };

  return { ...base, alertKey: messageKey(code) };
}

/** Starting billing input of the in-panel billing part. */
export const emptyBilling = () => ({ ...emptyAddress(), type: 'INDIVIDUAL' });

/**
 * The billing part of "pay another way" after BUYER_INFO_REQUIRED `{fields}`: the shared billing section limited to
 * the fields the server named (mode OFF shows only those). `{ req, open, errors, firstId, body }`; `body` is the
 * `billingInfo` to send, null while something is missing or when the server named nothing this theme can ask for.
 */
export function billingStep(fields, info) {
  const req = billingRequirements({
    config: { billingInfoMode: 'OFF' },
    quote: { requiredBuyerFields: Array.isArray(fields) ? fields : [] },
    info,
  });
  const errors = req.open ? validateBilling(req, info) : {};
  const valid = req.open && Object.keys(errors).length === 0;

  return {
    req,
    open: req.open,
    errors,
    firstId: firstInvalidId({ billing: errors }),
    body: valid ? effectiveBillingInfo(req, info) : null,
  };
}

// ---- bank transfer notice ---------------------------------------------------------------------------------

export const NOTE_MAX = 255;

/** The notify form shows for `buyerConfirms` on the built-in `bank-transfer` method, never read-only. */
export const showNotifyForm = ({ order, instructions, readonly = false }) =>
  !readonly && instructions?.buyerConfirms === true && order?.payment?.methodId === 'bank-transfer';

/** Strips control characters (kept as spaces) and trims. */
const clean = (value) => {
  let out = '';

  for (const char of String(value ?? '')) {
    const code = char.charCodeAt(0);
    out += code < 0x20 || code === 0x7f ? ' ' : char;
  }

  return out.trim();
};

/**
 * `{ ok: true, body }` (`senderName?`, `note?`, both optional, at most 255 characters, empty ones left out) or
 * `{ ok: false, errors: { senderName?, note? } }` with FIELD_INVALID.
 */
export function notifyBody({ senderName = '', note = '' } = {}) {
  const name = clean(senderName);
  const text = clean(note);
  const errors = {};

  if (name.length > NOTE_MAX) errors.senderName = 'FIELD_INVALID';
  if (text.length > NOTE_MAX) errors.note = 'FIELD_INVALID';

  if (Object.keys(errors).length > 0) return { ok: false, errors };

  const body = {};

  if (name) body.senderName = name;
  if (text) body.note = text;

  return { ok: true, body };
}

/** `notify` failed: `ORDER_NOT_PAYABLE` re-fetches (the state moved on), anything else is an alert. */
export function notifyFailure(res) {
  const code = res?.code ?? 'NETWORK';

  return code === 'ORDER_NOT_PAYABLE'
    ? { alertKey: messageKey(code), refetch: true }
    : { alertKey: messageKey(code), refetch: false };
}

// ---- instruction fields ---------------------------------------------------------------------------------------

/** Rows of the instruction list: `{ label, value, copyable }` for entries that carry a text label. */
export function instructionRows(instructions) {
  const rows = Array.isArray(instructions?.fields) ? instructions.fields : [];

  return rows
    .filter((row) => isObject(row) && typeof row.label === 'string' && row.label !== '')
    .map((row) => ({
      label: row.label,
      value: row.value === null || row.value === undefined ? '' : String(row.value),
      copyable:
        row.copyable === true &&
        row.value !== null &&
        row.value !== undefined &&
        String(row.value) !== '',
    }));
}
