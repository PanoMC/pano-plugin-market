// Pure logic of the RefundModal (13 §6.3, 07 §7, 21 §3). No Svelte, no SDK import: the modal builds
// its requests, validation and outcome handling from here so every rule is unit tested.
import { idempotencyKeyFor, marketPath } from './api.js';
import { isStaleError } from '../components/order-detail/actions.js';

export const REFUND_REASON_MAX = 255;
/** A manual refund records money that left outside the gateway: the reason is required (21 §3.6). */
export const MANUAL_REASON_MIN = 3;
export const PREVIEW_DEBOUNCE_MS = 300;

/** `ABOVE_REMAINING` etc. are keys under `modals.refund.error.*`. */
const EPS = 0.005;
const round2 = (n) => Math.round((Number(n) + Number.EPSILON) * 100) / 100;
const num = (v) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
};
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

// ---- modes ------------------------------------------------------------------------------------

export const REFUND_MODES = ['FULL', 'AMOUNT', 'ITEMS'];

/**
 * The radio options (13 §6.3 step 2) from `allowed.refundModes` (FULL, PARTIAL, PER_LINE, MANUAL):
 * Full is always there, Amount needs PARTIAL or MANUAL, Items needs PER_LINE, PARTIAL or MANUAL.
 */
export function offeredModes(refundModes) {
  const modes = Array.isArray(refundModes) ? refundModes : [];
  const has = (m) => modes.includes(m);
  const out = ['FULL'];
  if (has('PARTIAL') || has('MANUAL')) out.push('AMOUNT');
  if (has('PER_LINE') || has('PARTIAL') || has('MANUAL')) out.push('ITEMS');
  return out;
}

// ---- remaining amounts and the split override -------------------------------------------------

/** Gateway money still collected and not refunded (13 §6.3 step 5). */
export const remainingGateway = (order) =>
  Math.max(0, round2(num(order?.paidAmount) - num(order?.refundedGatewayAmount)));

/** Credits spent on the order and not refunded yet. */
export const remainingCredits = (order) =>
  Math.max(0, round2(num(order?.creditAmount) - num(order?.refundedCreditAmount)));

/** Money value of one credit on this order (`creditValue / creditAmount`), 0 without credits. */
export const creditUnitValue = (order) =>
  num(order?.creditAmount) > 0 ? num(order?.creditValue) / num(order?.creditAmount) : 0;

/** The override switch exists only on a mixed order (13 §6.3 step 5). */
export const canOverrideSplit = (order) =>
  num(order?.creditAmount) > 0 && num(order?.gatewayAmount) > 0;

/**
 * Validates an override split (13 §6.3 step 5, test 32). `gateway` is money, `credits` is credits;
 * an empty field (null / undefined / '') counts as 0. `limits` ({ maxGateway, maxCredit } of the
 * preview, which already subtract refunds in flight) can only tighten the order based limits.
 * Returns `{ ok, errors: { gateway?, credits?, total? }, total }` with `total = gateway + credits ×
 * unit value` rounded to cents. Error values: `INVALID`, `ABOVE_REMAINING`, `ZERO_TOTAL`.
 */
export function validateSplit(order, gateway, credits, limits = {}) {
  const errors = {};
  const empty = (v) => v === null || v === undefined || v === '';
  const check = (value, remaining, cap, key) => {
    if (empty(value)) return 0;
    const n = Number(value);
    if (!Number.isFinite(n) || n < 0) {
      errors[key] = 'INVALID';
      return 0;
    }
    const limit = isNum(cap) ? Math.min(remaining, cap) : remaining;
    if (n > limit + EPS) errors[key] = 'ABOVE_REMAINING';
    return n;
  };
  const g = check(gateway, remainingGateway(order), limits?.maxGateway, 'gateway');
  const c = check(credits, remainingCredits(order), limits?.maxCredit, 'credits');
  const total = round2(g + c * creditUnitValue(order));
  if (total <= EPS) errors.total = 'ZERO_TOTAL';
  return { ok: Object.keys(errors).length === 0, errors, total };
}

// ---- items ------------------------------------------------------------------------------------

/**
 * Lines the Items mode offers (13 §6.3 step 2, test 33): `quantity − refundedQuantity > 0` and kind
 * other than BUNDLE_CHILD (children follow their bundle). Each row gets `remaining`.
 */
export function refundableItems(items) {
  const out = [];
  for (const item of Array.isArray(items) ? items : []) {
    if (!item || item.kind === 'BUNDLE_CHILD') continue;
    const remaining = num(item.quantity) - num(item.refundedQuantity);
    if (remaining > 0) out.push({ ...item, remaining });
  }
  return out;
}

/**
 * `quantities` = { [orderItemId]: number | '' | null } -> `{ ok, items: [{ orderItemId, quantity }],
 * errors: { [id]: true } }`. A quantity is an integer 0…remaining; at least one must be above 0.
 */
export function itemsPayload(refundable, quantities) {
  const items = [];
  const errors = {};
  for (const row of refundable) {
    const raw = quantities?.[row.id];
    if (raw === null || raw === undefined || raw === '') continue;
    const q = Number(raw);
    if (!Number.isInteger(q) || q < 0 || q > row.remaining) {
      errors[row.id] = true;
      continue;
    }
    if (q > 0) items.push({ orderItemId: row.id, quantity: q });
  }
  return { ok: items.length > 0 && Object.keys(errors).length === 0, items, errors };
}

// ---- amount -----------------------------------------------------------------------------------

/** The highest amount: `allowed.refundMax` tightened by the preview's `max` (in-flight refunds). */
export function effectiveMax(allowed, preview) {
  const caps = [allowed?.refundMax, preview?.max].filter(isNum);
  return caps.length === 0 ? null : Math.min(...caps);
}

/** `null` when valid, else `INVALID` (empty / NaN / not above 0) or `ABOVE_MAX`. */
export function amountError(amount, max) {
  if (!isNum(amount) || amount <= 0) return 'INVALID';
  if (isNum(max) && amount > max + EPS) return 'ABOVE_MAX';
  return null;
}

// ---- preview ----------------------------------------------------------------------------------

/**
 * Path of `GET /orders/:id/refund-preview` for the form, or null while the input is not valid
 * (no request is made for it). FULL sends no parameter, AMOUNT `amount`, ITEMS `items` (JSON).
 */
export function previewPath(orderId, form, refundable = []) {
  const base = `/orders/${orderId}/refund-preview`;
  if (form.mode === 'AMOUNT') {
    return amountError(form.amount, null) === null
      ? marketPath(`${base}?amount=${encodeURIComponent(String(form.amount))}`)
      : null;
  }
  if (form.mode === 'ITEMS') {
    const built = itemsPayload(refundable, form.quantities);
    return built.ok
      ? marketPath(`${base}?items=${encodeURIComponent(JSON.stringify(built.items))}`)
      : null;
  }
  return marketPath(base);
}

/**
 * True when the preview on screen was fetched for the form as it is now: `previewFor` is the path
 * the shown preview answered, `path` the one the current form maps to (null = invalid input). False
 * while a preview is debounced or in flight, so the CTA never confirms a total / split the dialog
 * did not show (13 §6.3, 07 §7.3).
 */
export const previewIsCurrent = (path, previewFor, status) =>
  path !== null && path !== undefined && path === previewFor && status === 'READY';

/**
 * Whether the submit button (and the form's Enter key) may send the refund. `closing` is set once
 * the modal decided to close (success, provider error, stale): from then on nothing is sent, so a
 * double click during the fade can never start a second refund.
 */
export function canSubmitRefund({
  status,
  closing = false,
  previewCurrent = false,
  hasPreview = false,
  loadError = null,
  amountMax = null,
  valid = false,
}) {
  return (
    !closing &&
    status !== 'SUBMITTING' &&
    status !== 'LOADING' &&
    status !== 'PREVIEWING' &&
    previewCurrent === true &&
    hasPreview === true &&
    !loadError &&
    amountMax === null &&
    valid === true
  );
}

/** Last request wins: `next()` tags a request, `isCurrent(tag)` is false once a newer one started. */
export function latestWins() {
  let counter = 0;
  return {
    next: () => ++counter,
    isCurrent: (tag) => tag === counter,
    cancel: () => void ++counter,
  };
}

const warningsOf = (preview) => (Array.isArray(preview?.warnings) ? preview.warnings : []);
const warningOf = (preview, code) => warningsOf(preview).find((w) => w?.code === code) ?? null;

/** Warning codes the modal renders as its own line (the split box covers MIXED_PAYMENT_SPLIT). */
export const LISTED_WARNINGS = [
  'CREDIT_ONLY_REFUND',
  'CASHBACK_REVERSAL',
  'CREDIT_CLAWBACK',
  'GATEWAY_PARTIAL_REFUND_NOT_SUPPORTED',
  'CREDIT_ACCOUNT_CLOSED',
  'UPGRADE_DEPENDENT',
  'OLDER_SUBSCRIPTION_PERIOD',
];

/** Warnings of the preview for the list; an unknown code is kept and rendered generically. */
export const listedWarnings = (preview) =>
  warningsOf(preview).filter(
    (w) => w && typeof w.code === 'string' && w.code !== 'MIXED_PAYMENT_SPLIT',
  );

export const upgradeDependent = (preview) => warningOf(preview, 'UPGRADE_DEPENDENT') !== null;

/**
 * The split warning (13 §6.3 step 4) is shown whenever credits are involved: the preview returns
 * credits, or a mixed / credit-only warning, or the order itself was paid with credits (so it is
 * visible before the preview arrives).
 */
export function splitWarningVisible(order, preview) {
  return (
    num(preview?.creditAmount) > 0 ||
    warningOf(preview, 'MIXED_PAYMENT_SPLIT') !== null ||
    warningOf(preview, 'CREDIT_ONLY_REFUND') !== null ||
    num(order?.creditAmount) > 0
  );
}

/**
 * True when the refund really goes to both the gateway and the credit account: the admin must tick
 * "I understand this refund is split" (07 §7.3, UI rule only).
 */
export function requiresSplitAck(order, preview, form = {}) {
  if (form.override === true && canOverrideSplit(order)) {
    return num(form.gateway) > 0 && num(form.credits) > 0;
  }
  return (
    warningOf(preview, 'MIXED_PAYMENT_SPLIT') !== null ||
    (num(preview?.gatewayAmount) > 0 && num(preview?.creditAmount) > 0)
  );
}

/** The provider cannot refund (or is unavailable): the modal forces the manual switch on. */
export const manualForced = (preview) => preview?.mode === 'MANUAL';

/** Total shown on the CTA: the override sum when it is on, else the preview's amount (null = unknown). */
export function refundTotal(order, form, preview) {
  if (form.override === true && canOverrideSplit(order)) {
    return validateSplit(order, form.gateway, form.credits).total;
  }
  return isNum(preview?.amount) ? preview.amount : null;
}

// ---- submit -----------------------------------------------------------------------------------

/** Fresh form state (13 §6.3 step 1): ctx.revokeOnRefund decides the revoke default. */
export function initialForm(ctx) {
  return {
    mode: 'FULL',
    amount: null,
    quantities: {},
    override: false,
    gateway: null,
    credits: null,
    reason: '',
    revoke: ctx?.revokeOnRefund === true,
    restock: false,
    manual: false,
    revokeFirst: true,
    cascade: null,
    ack: false,
  };
}

/**
 * Validates the form and builds `POST /orders/:id/refunds`. Returns `{ request: { method, path,
 * body } }` or `{ error: { amount?, items?, gateway?, credits?, total?, reason?, cascade?, ack? } }`
 * (true per invalid field). The Idempotency-Key header is added by `refundHeaders`.
 */
export function buildRefundRequest(order, form, preview, refundable = [], allowed = null) {
  const error = {};
  const override = form.override === true && canOverrideSplit(order);
  const manual = manualForced(preview) || form.manual === true;
  const body = {};

  if (override) {
    const split = validateSplit(order, form.gateway, form.credits, {
      maxGateway: preview?.maxGateway,
      maxCredit: preview?.maxCredit,
    });
    if (split.errors.gateway) error.gateway = true;
    if (split.errors.credits) error.credits = true;
    if (split.errors.total) error.total = true;
    body.gatewayAmount = num(form.gateway);
    body.creditAmount = num(form.credits);
  } else if (form.mode === 'AMOUNT') {
    if (amountError(form.amount, effectiveMax(allowed, preview)) !== null) error.amount = true;
    else body.amount = form.amount;
  }
  if (form.mode === 'ITEMS') {
    const built = itemsPayload(refundable, form.quantities);
    if (!built.ok) error.items = true;
    else body.items = built.items;
  }

  const reason = String(form.reason ?? '').trim();
  if (reason.length > REFUND_REASON_MAX) error.reason = true;
  if (manual && reason.length < MANUAL_REASON_MIN) error.reason = true;
  if (reason !== '') body.reason = reason;

  body.revoke = form.revoke === true;
  body.restock = form.restock === true;
  body.manual = manual;
  if (preview?.recommendRevokeFirst === true && form.revokeFirst === true) body.revokeFirst = true;
  if (upgradeDependent(preview)) {
    if (typeof form.cascade !== 'boolean') error.cascade = true;
    else body.cascadeUpgrade = form.cascade;
  }
  if (requiresSplitAck(order, preview, form) && form.ack !== true) error.ack = true;

  if (Object.keys(error).length > 0) return { error };
  return { request: { method: 'POST', path: marketPath(`/orders/${order.id}/refunds`), body } };
}

/** One key per unchanged body (retry, double click); a changed body gets a new key (13 §3.3). */
export function refundHeaders(idempotency, body) {
  return { 'Idempotency-Key': idempotencyKeyFor(idempotency, body) };
}

/**
 * What the modal does with the `call()` result of the POST (13 §6.3 step 7). `reset`: start the
 * idempotency state over (a conflict only); a network error keeps it so the retry is safe.
 * kinds: done (toast key), manual, providerError, invalidAmount (max), stale, error. A success and a
 * provider error keep the key (the modal closes; a late replay of the same body gets the same refund
 * back instead of a new one); `open()` starts every session with a fresh key.
 */
export function refundOutcome(result) {
  if (result.ok) {
    const status = result.body?.refund?.status;
    return {
      kind: 'done',
      toast: status === 'SUCCEEDED' ? 'modals.refund.toast-success' : 'modals.refund.toast-pending',
      reset: false,
    };
  }
  switch (result.error) {
    case 'REFUND_NOT_SUPPORTED':
      return { kind: 'manual', reset: false };
    case 'PAYMENT_PROVIDER_ERROR':
      return { kind: 'providerError', reset: false };
    case 'INVALID_REFUND_AMOUNT':
      return {
        kind: 'invalidAmount',
        max: isNum(result.body?.max) ? result.body.max : null,
        reset: false,
      };
    case 'IDEMPOTENCY_CONFLICT':
      return { kind: 'error', reset: true };
    default:
      return isStaleError(result.error)
        ? { kind: 'stale', reset: false }
        : { kind: 'error', reset: false };
  }
}
