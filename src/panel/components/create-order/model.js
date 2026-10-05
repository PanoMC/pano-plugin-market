// Pure state helpers of the manual order page (13 §7): cart lines, product line builder, request body,
// quote matching, submit outcomes, debounced last-wins quote runner. No Svelte, no SDK import.
import { idempotencyKeyFor } from '../../utils/api.js';
import { isEmailLike, isMinecraftUsername } from '../../utils/validate.js';

export const NOTE_MAX = 2000;
export const LABEL_MAX = 255;
export const QUOTE_DEBOUNCE_MS = 400;

/** Line codes of QuoteLine.errors that a force flag lifts (06 §14.3). */
export const LIMIT_LINE_CODES = [
  'MAX_QUANTITY',
  'OUT_OF_STOCK',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'REQUIREMENT_NOT_MET',
  'PRODUCT_UNAVAILABLE',
  'ALREADY_OWNED',
];

/** Submit error codes that offer "Enable Ignore Limits And Stock". */
export const FORCEABLE_ERRORS = [
  'OUT_OF_STOCK',
  'PURCHASE_LIMIT_REACHED',
  'COOLDOWN_ACTIVE',
  'PRODUCT_REQUIREMENT_NOT_MET',
];

/** Line codes the toggle cannot lift (the line is wrong, not over a limit). */
export const isLimitCode = (code) => LIMIT_LINE_CODES.includes(code);

// ---------------------------------------------------------------- ?player=

/** Pre-fill of the buyer from `?player=`; '' when absent. Never longer than 32 characters. */
export function playerFromSearch(search) {
  let value = '';
  try {
    value = new URLSearchParams(search ?? '').get('player') ?? '';
  } catch {
    value = '';
  }
  return value.trim().slice(0, 32);
}

// ---------------------------------------------------------------- cart lines

function canonicalValue(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  const text = String(value);
  return text === '' ? null : text;
}

/** fieldValues without empty entries, keys sorted: the same payload always serialises the same. */
export function canonicalFieldValues(fieldValues) {
  const out = {};
  for (const key of Object.keys(fieldValues ?? {}).sort()) {
    const value = canonicalValue(fieldValues[key]);
    if (value !== null) out[key] = value;
  }
  return out;
}

/** Identity of a line: product, variant, canonical fieldValues, server (13 §7). */
export function lineIdentity(line) {
  return JSON.stringify([
    line.productId,
    line.variantId ?? null,
    canonicalFieldValues(line.fieldValues),
    line.targetServerId ?? null,
  ]);
}

/** Adds a line; an identical one increases the quantity of the existing line instead. */
export function addLine(lines, line) {
  const identity = lineIdentity(line);
  const index = lines.findIndex((l) => lineIdentity(l) === identity);
  if (index === -1) return [...lines, { ...line }];
  return lines.map((l, i) =>
    i === index ? { ...l, quantity: Number(l.quantity) + Number(line.quantity) } : l,
  );
}

export function removeLine(lines, index) {
  return lines.filter((_, i) => i !== index);
}

export function setQuantity(lines, index, quantity) {
  return lines.map((l, i) => (i === index ? { ...l, quantity } : l));
}

/** A quantity input value: integer >= 1 (and <= max unless `force`), else a code. */
export function quantityError(quantity, { max = null, force = false } = {}) {
  const n = Number(quantity);
  if (quantity === '' || quantity === null || quantity === undefined) return 'REQUIRED';
  if (!Number.isInteger(n) || n < 1) return 'INVALID';
  if (!force && max !== null && max !== undefined && n > max) return 'MAX_QUANTITY';
  return null;
}

// ---------------------------------------------------------------- product -> line (AddOrderItemModal)

/** Variants a buyer can pick: ACTIVE only. */
export function activeVariants(product) {
  return (product?.variants ?? []).filter((v) => (v.status ?? 'ACTIVE') === 'ACTIVE');
}

/** True when an action needs the server picked at order time. */
export function needsServerChoice(product) {
  return (product?.actions ?? []).some((a) => a?.serverMode === 'BUYER_CHOICE');
}

/** serverChoices of the product that still exist in GET /servers. */
export function serverOptions(product, servers) {
  const known = new Map((servers ?? []).map((s) => [Number(s.id), s]));
  return (product?.serverChoices ?? [])
    .map(Number)
    .filter((id) => known.has(id))
    .map((id) => known.get(id));
}

/** Initial value of one field input: its default, or false for a checkbox. */
export function fieldInitial(field) {
  if (field.type === 'CHECKBOX')
    return field.defaultValue === 'true' || field.defaultValue === true;
  return field.defaultValue ?? '';
}

/** Error code of one custom field value (08 §2 / 13 §8.5 preview renderer), null when valid. */
export function fieldError(field, value) {
  const text = value === null || value === undefined ? '' : String(value);
  if (field.type === 'CHECKBOX')
    return field.required && value !== true && value !== 'true' ? 'REQUIRED' : null;
  if (text === '') return field.required ? 'REQUIRED' : null;
  switch (field.type) {
    case 'USERNAME':
      return isMinecraftUsername(text) ? null : 'INVALID';
    case 'DISCORD_ID':
      return /^\d{17,20}$/.test(text) ? null : 'INVALID';
    case 'EMAIL':
      return isEmailLike(text) ? null : 'INVALID';
    case 'NUMBER': {
      if (!/^-?\d+$/.test(text)) return 'INVALID';
      const n = Number(text);
      if (field.minValue !== null && field.minValue !== undefined && n < field.minValue)
        return 'INVALID';
      if (field.maxValue !== null && field.maxValue !== undefined && n > field.maxValue)
        return 'INVALID';
      return null;
    }
    case 'SELECT':
      return (field.options ?? []).some((o) => o.value === text) ? null : 'INVALID';
    default: {
      if (/[\r\n]/.test(text)) return 'INVALID';
      if (field.minLength && text.length < field.minLength) return 'INVALID';
      if (field.maxLength && text.length > field.maxLength) return 'INVALID';
      if (field.pattern) {
        try {
          if (!new RegExp('^(?:' + field.pattern + ')$').test(text)) return 'INVALID';
        } catch {
          return null;
        }
      }
      return null;
    }
  }
}

/**
 * Validates the modal selection and builds the CartLine. `selection` = { variantId, quantity,
 * values: {fieldKey: value}, serverId }. Returns `{ line }` or `{ errors: { variant?, quantity?,
 * serverId?, fields: {key: code} } }`.
 */
export function buildLine(product, selection, { servers = [], force = false } = {}) {
  const errors = { fields: {} };
  const variants = activeVariants(product);
  let variantId = null;
  if (product?.hasVariants) {
    const id = Number(selection.variantId);
    if (!variants.some((v) => Number(v.id) === id)) errors.variant = 'REQUIRED';
    else variantId = id;
  }
  const qty = quantityError(selection.quantity, {
    max: product?.maxQuantityPerOrder ?? null,
    force,
  });
  if (qty) errors.quantity = qty;

  const fieldValues = {};
  for (const field of product?.fields ?? []) {
    const value = selection.values?.[field.fieldKey];
    const code = fieldError(field, value);
    if (code) errors.fields[field.fieldKey] = code;
    else {
      const canonical =
        field.type === 'CHECKBOX'
          ? value === true || value === 'true'
            ? 'true'
            : null
          : canonicalValue(value);
      if (canonical !== null) fieldValues[field.fieldKey] = canonical;
    }
  }

  let targetServerId = null;
  if (needsServerChoice(product)) {
    const id = Number(selection.serverId);
    if (!serverOptions(product, servers).some((s) => Number(s.id) === id))
      errors.serverId = 'REQUIRED';
    else targetServerId = id;
  }

  if (
    errors.variant ||
    errors.quantity ||
    errors.serverId ||
    Object.keys(errors.fields).length > 0
  ) {
    return { errors };
  }
  const line = { productId: Number(product.id), quantity: Number(selection.quantity) };
  if (variantId !== null) line.variantId = variantId;
  if (Object.keys(fieldValues).length > 0) line.fieldValues = fieldValues;
  if (targetServerId !== null) line.targetServerId = targetServerId;
  return { line };
}

// ---------------------------------------------------------------- form validation and body

export function initialForm(player = '') {
  return {
    playerUsername: player,
    gift: false,
    recipientUsername: '',
    email: '',
    override: false,
    priceOverride: null,
    markPaid: true,
    paymentLabel: '',
    runDeliveries: true,
    sendMail: false,
    force: false,
    note: '',
  };
}

/** The deliveries switch only means something for a paid order. */
export const effectiveRunDeliveries = (form) => form.markPaid && form.runDeliveries;

/**
 * Mail switch availability: an e-mail was typed, or the quote does not say the buyer is unknown
 * (`quote.buyerResolved === false`; the contract has no such field yet, absent = allowed and the
 * server answers MAIL_RECIPIENT_REQUIRED when there is really no address).
 */
export function canSendMail(form, quote) {
  if (String(form.email ?? '').trim() !== '') return true;
  return quote?.buyerResolved !== false;
}

/** Field errors of the form: { playerUsername?, recipientUsername?, email?, priceOverride?, paymentLabel?, note? }. */
export function formErrors(form) {
  const errors = {};
  const player = String(form.playerUsername ?? '').trim();
  if (!isMinecraftUsername(player)) errors.playerUsername = 'INVALID';
  if (form.gift) {
    const recipient = String(form.recipientUsername ?? '').trim();
    if (!isMinecraftUsername(recipient)) errors.recipientUsername = 'INVALID';
    else if (recipient.toLowerCase() === player.toLowerCase())
      errors.recipientUsername = 'SAME_AS_BUYER';
  }
  const email = String(form.email ?? '').trim();
  if (email !== '' && !isEmailLike(email)) errors.email = 'INVALID';
  if (form.override) {
    const value = form.priceOverride;
    if (value === null || value === undefined || Number.isNaN(value) || value < 0)
      errors.priceOverride = 'INVALID';
  }
  if (form.markPaid && String(form.paymentLabel ?? '').length > LABEL_MAX)
    errors.paymentLabel = 'TOO_LONG';
  if (String(form.note ?? '').length > NOTE_MAX) errors.note = 'TOO_LONG';
  return errors;
}

/**
 * Body shared by POST /orders and POST /orders/quote (04 §7). `defaultLabel` is the translated default
 * payment label. Never includes internal row ids.
 */
export function buildBody(form, lines, { defaultLabel = '' } = {}) {
  const body = {
    playerUsername: String(form.playerUsername ?? '').trim(),
    items: lines.map((l) => {
      const item = { productId: l.productId, quantity: Number(l.quantity) };
      if (l.variantId !== undefined && l.variantId !== null) item.variantId = l.variantId;
      const values = canonicalFieldValues(l.fieldValues);
      if (Object.keys(values).length > 0) item.fieldValues = values;
      if (l.targetServerId !== undefined && l.targetServerId !== null)
        item.targetServerId = l.targetServerId;
      return item;
    }),
    markPaid: !!form.markPaid,
    runDeliveries: effectiveRunDeliveries(form),
    sendMail: !!form.sendMail,
  };
  if (form.gift && String(form.recipientUsername ?? '').trim() !== '') {
    body.recipientUsername = String(form.recipientUsername).trim();
  }
  const email = String(form.email ?? '').trim();
  if (email !== '') body.email = email;
  if (
    form.override &&
    typeof form.priceOverride === 'number' &&
    !Number.isNaN(form.priceOverride)
  ) {
    body.priceOverride = form.priceOverride;
  }
  if (form.markPaid) {
    const label = String(form.paymentLabel ?? '').trim();
    body.paymentLabel = label !== '' ? label : defaultLabel;
  }
  const note = String(form.note ?? '').trim();
  if (note !== '') body.note = note;
  if (form.force) body.force = true;
  return body;
}

// ---------------------------------------------------------------- quote matching

/**
 * Quote lines matched to cart lines by index. Bundle children are folded into their parent (the
 * request has one entry per bundle). Returns an array parallel to `lines`, entries null when the
 * quote does not cover that index.
 */
export function matchQuoteLines(quote, lines) {
  const all = quote?.lines;
  if (!Array.isArray(all)) return lines.map(() => null);
  const result = [];
  for (const q of all) {
    if (q?.kind === 'BUNDLE_CHILD') {
      const parent = result[result.length - 1];
      if (parent && q.errors?.length)
        parent.errors = [...new Set([...(parent.errors ?? []), ...q.errors])];
      continue;
    }
    result.push({ ...q, errors: [...(q?.errors ?? [])] });
  }
  return lines.map((_, i) => result[i] ?? null);
}

/** Per-line error codes: quote errors plus the server's INVALID_CART lineErrors, plus local MAX_QUANTITY. */
export function lineErrorCodes(quoteLine, serverErrors = []) {
  return [...new Set([...(quoteLine?.errors ?? []), ...(serverErrors ?? [])])];
}

/** Lines whose errors are limit codes (those the force toggle lifts). */
export function overLimitIndexes(matched, serverErrors = []) {
  const out = [];
  matched.forEach((q, i) => {
    if (lineErrorCodes(q, serverErrors[i]).some(isLimitCode)) out.push(i);
  });
  return out;
}

/**
 * Whether the line problems stop the order. With `force` nothing the toggle lifts stops it; any other
 * code (variant gone, field invalid, server unavailable ...) always stops it. Without a quote the
 * server decides (13 §7).
 */
export function blockingLineCodes(codes, force) {
  return codes.filter((code) => !(force && isLimitCode(code)));
}

/** CTA state. `quote` null = no successful quote yet. */
export function submitState({ form, lines, quote, serverErrors = [], saving = false }) {
  if (saving) return { canSubmit: false, reason: 'SAVING' };
  if (lines.length === 0) return { canSubmit: false, reason: 'NO_LINES' };
  if (Object.keys(formErrors(form)).length > 0) return { canSubmit: false, reason: 'INVALID_FORM' };
  if (lines.some((l) => quantityError(l.quantity, { force: true }) !== null)) {
    return { canSubmit: false, reason: 'INVALID_LINE' };
  }
  const matched = matchQuoteLines(quote, lines);
  for (let i = 0; i < lines.length; i++) {
    const codes = lineErrorCodes(matched[i], serverErrors[i]);
    if (blockingLineCodes(codes, form.force).length > 0) {
      return {
        canSubmit: false,
        reason: codes.some((c) => !isLimitCode(c)) ? 'LINE_ERROR' : 'OVER_LIMIT',
      };
    }
  }
  if (quote && quote.canCheckout === false && !form.force) {
    return { canSubmit: false, reason: 'OVER_LIMIT' };
  }
  return { canSubmit: true, reason: null };
}

// ---------------------------------------------------------------- submit

/** Headers of POST /orders: one Idempotency-Key per unchanged body. */
export function submitHeaders(state, body) {
  return { 'Idempotency-Key': idempotencyKeyFor(state, body) };
}

/** serverErrors by index from INVALID_CART `lineErrors{lineKey: CODE[]}` (key order = request order). */
export function mapLineErrors(lineErrors, count) {
  const out = Array.from({ length: count }, () => []);
  if (!lineErrors || typeof lineErrors !== 'object') return out;
  Object.keys(lineErrors).forEach((key, i) => {
    if (i < count && Array.isArray(lineErrors[key])) out[i] = lineErrors[key].map(String);
  });
  return out;
}

/**
 * Reaction to a failed POST /orders. Returns `{ toast: code, offerForce, field, lineErrors, reset }`:
 * toast = errors.<CODE>; offerForce = show the "Enable Ignore Limits And Stock" alert; field marks a
 * form field; lineErrors by index; reset = drop the idempotency state (IDEMPOTENCY_CONFLICT).
 */
export function submitFailure(code, body, count) {
  const outcome = { toast: code, offerForce: false, field: null, lineErrors: null, reset: false };
  if (code === 'INVALID_CART') outcome.lineErrors = mapLineErrors(body?.lineErrors, count);
  else if (FORCEABLE_ERRORS.includes(code)) outcome.offerForce = true;
  else if (code === 'INVALID_RECIPIENT') outcome.field = 'recipientUsername';
  else if (code === 'SUBSCRIPTION_MUST_BE_ALONE')
    outcome.lineErrors = Array.from({ length: count }, () => [code]);
  else if (code === 'IDEMPOTENCY_CONFLICT') outcome.reset = true;
  return outcome;
}

/** `/market/orders/detail/<id>` of a created order; null when the answer carries no id. */
export function detailPath(body) {
  const id = Number(body?.id);
  return Number.isInteger(id) && id > 0 ? `/market/orders/detail/${id}` : null;
}

// ---------------------------------------------------------------- debounced last-wins quote runner

/**
 * `send(body)` -> Promise<{ ok, body }>. `schedule(body)` restarts the debounce; only the answer of the
 * newest request is applied (`onResult(quote | null)`); `cancel()` drops anything pending or in flight.
 * `timers` = { set, clear } injectable for tests.
 */
export function createQuoteRunner({
  send,
  onResult,
  onPending = () => {},
  delay = QUOTE_DEBOUNCE_MS,
  timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (id) => clearTimeout(id) },
}) {
  let timer = null;
  let sequence = 0;
  return {
    schedule(body) {
      if (timer !== null) timers.clear(timer);
      const mine = ++sequence;
      onPending(true);
      timer = timers.set(async () => {
        timer = null;
        let result;
        try {
          result = await send(body);
        } catch {
          result = { ok: false };
        }
        if (mine !== sequence) return;
        onPending(false);
        onResult(result?.ok && result.body?.quote ? result.body.quote : null);
      }, delay);
    },
    cancel() {
      if (timer !== null) timers.clear(timer);
      timer = null;
      sequence++;
      onPending(false);
    },
  };
}

// ---------------------------------------------------------------- texts

/** Every line code this page can show; others fall back to enums.line-error.UNKNOWN. */
export const LINE_ERROR_CODES = [
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
  'LOGIN_REQUIRED',
  'NOT_IN_CURRENCY',
  'SUBSCRIPTION_MUST_BE_ALONE',
];

export function lineErrorKey(code) {
  return LINE_ERROR_CODES.includes(code) ? `enums.line-error.${code}` : 'enums.line-error.UNKNOWN';
}
