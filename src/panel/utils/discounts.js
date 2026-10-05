// Pure logic of the discounts area (13 §12): form validation, request bodies, payout checks,
// report filtering. No Svelte, no SDK import.
import { idempotencyKeyFor } from './api.js';
import { dayToEpoch, parseInteger, parseMoney, toDateInput } from './format.js';
import { serializeActions, validateActions } from './actions.js';

export const UNITS = ['PERCENT', 'FIXED'];
export const NAME_MAX = 255;
export const CODE_MAX = 64;
export const NOTE_MAX = 255;
export const PAYOUT_METHODS = ['CREDIT', 'ACTION', 'MANUAL'];
export const PAYOUT_ACTION_TYPES = ['COMMAND', 'PERMISSION', 'WEBHOOK'];
export const EARNING_STATES = ['AVAILABLE', 'PAID', 'REVERSED'];

/** Counters the server owns (atomic increments, bug 2): never part of a PUT / POST body. */
export const SERVER_OWNED_COUNTERS = ['usedCount', 'earnings', 'paidOut'];

/** Defensive copy without the counters; request builders never add them, this is the last guard. */
export function stripCounters(body) {
  const out = { ...body };
  for (const key of SERVER_OWNED_COUNTERS) delete out[key];
  return out;
}

const text = (value) => String(value ?? '').trim();

/** Server enum or legacy symbol of an older row -> 'PERCENT' | 'FIXED'. */
export const unitOf = (value) => (value === 'FIXED' ? 'FIXED' : 'PERCENT');

/**
 * Discount / coupon / creator value: PERCENT 0-100 (up to 2 decimals), FIXED >= 0 with the currency
 * exponent. Returns { value } or { error: REQUIRED | INVALID | TOO_LARGE }.
 */
export function checkValue(raw, unit, exponent = 2) {
  const source = text(raw);
  if (source === '') return { error: 'REQUIRED' };
  const value = parseMoney(source, unit === 'FIXED' ? exponent : 2);
  if (value === null) return { error: 'REQUIRED' };
  if (Number.isNaN(value)) return { error: 'INVALID' };
  if (unit !== 'FIXED' && value > 100) return { error: 'TOO_LARGE' };
  return { value };
}

/** Optional money (minimum cart amount): empty = null, else >= 0. */
export function checkOptionalMoney(raw, exponent = 2) {
  if (text(raw) === '') return { value: null };
  const value = parseMoney(text(raw), exponent);
  if (Number.isNaN(value)) return { error: 'INVALID' };
  return { value };
}

/** A limit that is unlimited (null) or an integer >= 0. */
export function checkLimit(raw, unlimited) {
  if (unlimited) return { value: null };
  if (text(raw) === '') return { error: 'REQUIRED' };
  const value = parseInteger(raw, { min: 0, max: 2_147_483_647 });
  if (Number.isNaN(value)) return { error: 'INVALID' };
  return { value };
}

/** Commission percent 0-100, empty = 0. */
export function checkCommission(raw) {
  if (text(raw) === '') return { value: 0 };
  const value = parseMoney(text(raw), 2);
  if (Number.isNaN(value)) return { error: 'INVALID' };
  if (value > 100) return { error: 'TOO_LARGE' };
  return { value };
}

/** Start / end day inputs: { start, end } epochs (null = open) or { error: DATES_REVERSED }. */
export function checkDates(unlimited, startText, endText) {
  if (unlimited) return { start: null, end: null };
  const start = dayToEpoch(startText, false);
  const end = dayToEpoch(endText, true);
  if (start !== null && end !== null && start > end) return { error: 'DATES_REVERSED' };
  return { start, end };
}

function required(value, max) {
  const t = text(value);
  if (t === '') return 'REQUIRED';
  if (t.length > max) return 'TOO_LONG';
  return null;
}

function finish(errors, body) {
  return Object.keys(errors).length > 0 ? { errors } : { body: stripCounters(body) };
}

function scopeIds(errors, scope, key, ids, wanted) {
  if (scope !== wanted) return null;
  const list = Array.isArray(ids) ? ids : [];
  if (list.length === 0) errors[key] = 'REQUIRED';
  return list;
}

/**
 * Discount form -> { errors } (field -> CODE) or { body }. form: name, value, unit, minPaymentAmount,
 * scope (ALL | PRODUCTS | CATEGORIES), productIds, categoryIds, active, unlimitedDates, startDate,
 * expiryDate, unlimitedUsage, usageLimit, showBadge. `exponent` = exponent of the store currency.
 */
export function buildDiscountBody(form, exponent = 2) {
  const errors = {};
  const unit = unitOf(form.unit);
  const nameError = required(form.name, NAME_MAX);
  if (nameError) errors.name = nameError;
  const value = checkValue(form.value, unit, exponent);
  if (value.error) errors.value = value.error;
  const min = checkOptionalMoney(form.minPaymentAmount, exponent);
  if (min.error) errors.minPaymentAmount = min.error;
  const dates = checkDates(form.unlimitedDates, form.startDate, form.expiryDate);
  if (dates.error) errors.expiryDate = dates.error;
  const limit = checkLimit(form.usageLimit, form.unlimitedUsage);
  if (limit.error) errors.usageLimit = limit.error;
  const scope = ['PRODUCTS', 'CATEGORIES'].includes(form.scope) ? form.scope : 'ALL';
  const productIds = scopeIds(errors, scope, 'productIds', form.productIds, 'PRODUCTS');
  const categoryIds = scopeIds(errors, scope, 'categoryIds', form.categoryIds, 'CATEGORIES');

  const body = {
    name: text(form.name),
    value: value.value,
    unit,
    scope,
    status: form.active ? 'ACTIVE' : 'INACTIVE',
    showBadge: form.showBadge === true,
  };
  if (min.value !== null && min.value !== undefined) body.minPaymentAmount = min.value;
  if (productIds) body.productIds = productIds;
  if (categoryIds) body.categoryIds = categoryIds;
  if (dates.start !== null && dates.start !== undefined) body.startDate = dates.start;
  if (dates.end !== null && dates.end !== undefined) body.expiryDate = dates.end;
  if (limit.value !== null && limit.value !== undefined) body.usageLimit = limit.value;
  return finish(errors, body);
}

/**
 * Coupon form -> { errors } or { body }. form: name, code, discount, unit, minPaymentAmount,
 * scope (ALL | SELECTED), productIds, categoryIds, active, unlimitedDates, startDate, expiryDate,
 * unlimitedRedeem, redeemLimit, unlimitedCustomerRedeem, customerRedeemLimit.
 */
export function buildCouponBody(form, exponent = 2) {
  const errors = {};
  const unit = unitOf(form.unit);
  const codeError = required(form.code, CODE_MAX);
  if (codeError) errors.code = codeError;
  if (text(form.name).length > NAME_MAX) errors.name = 'TOO_LONG';
  const discount = checkValue(form.discount, unit, exponent);
  if (discount.error) errors.discount = discount.error;
  const min = checkOptionalMoney(form.minPaymentAmount, exponent);
  if (min.error) errors.minPaymentAmount = min.error;
  const dates = checkDates(form.unlimitedDates, form.startDate, form.expiryDate);
  if (dates.error) errors.expiryDate = dates.error;
  const redeem = checkLimit(form.redeemLimit, form.unlimitedRedeem);
  if (redeem.error) errors.redeemLimit = redeem.error;
  const customer = checkLimit(form.customerRedeemLimit, form.unlimitedCustomerRedeem);
  if (customer.error) errors.customerRedeemLimit = customer.error;
  const scope = form.scope === 'SELECTED' ? 'SELECTED' : 'ALL';
  const productIds = Array.isArray(form.productIds) ? form.productIds : [];
  const categoryIds = Array.isArray(form.categoryIds) ? form.categoryIds : [];
  if (scope === 'SELECTED' && productIds.length === 0 && categoryIds.length === 0)
    errors.productIds = 'REQUIRED';

  const body = {
    name: text(form.name),
    code: text(form.code),
    discount: discount.value,
    unit,
    scope,
    status: form.active ? 'ACTIVE' : 'INACTIVE',
  };
  if (min.value !== null && min.value !== undefined) body.minPaymentAmount = min.value;
  if (scope === 'SELECTED') {
    body.productIds = productIds;
    body.categoryIds = categoryIds;
  }
  if (dates.start !== null && dates.start !== undefined) body.startDate = dates.start;
  if (dates.end !== null && dates.end !== undefined) body.expiryDate = dates.end;
  if (redeem.value !== null && redeem.value !== undefined) body.redeemLimit = redeem.value;
  if (customer.value !== null && customer.value !== undefined)
    body.customerRedeemLimit = customer.value;
  return finish(errors, body);
}

/**
 * Creator code form -> { errors } or { body }. No `creatorUserId` is ever sent: the server resolves it
 * from `creator` (04 §6). form: creator, code, discount, unit, commission, active, unlimitedDates,
 * startDate, expiryDate, unlimitedRedeem, redeemLimit.
 */
export function buildCreatorBody(form, exponent = 2) {
  const errors = {};
  const unit = unitOf(form.unit);
  const creatorError = required(form.creator, NAME_MAX);
  if (creatorError) errors.creator = creatorError;
  const codeError = required(form.code, CODE_MAX);
  if (codeError) errors.code = codeError;
  const discount = checkValue(form.discount, unit, exponent);
  if (discount.error) errors.discount = discount.error;
  const commission = checkCommission(form.commission);
  if (commission.error) errors.commission = commission.error;
  const dates = checkDates(form.unlimitedDates, form.startDate, form.expiryDate);
  if (dates.error) errors.expiryDate = dates.error;
  const redeem = checkLimit(form.redeemLimit, form.unlimitedRedeem);
  if (redeem.error) errors.redeemLimit = redeem.error;

  const body = {
    creator: text(form.creator),
    code: text(form.code),
    discount: discount.value,
    unit,
    commissionPercent: commission.value,
    status: form.active ? 'ACTIVE' : 'INACTIVE',
  };
  if (dates.start !== null && dates.start !== undefined) body.startDate = dates.start;
  if (dates.end !== null && dates.end !== undefined) body.expiryDate = dates.end;
  if (redeem.value !== null && redeem.value !== undefined) body.redeemLimit = redeem.value;
  return finish(errors, body);
}

/** Row of a list -> the day inputs of the modal. */
export function datesToForm(row) {
  const has = Boolean(row?.startDate || row?.expiryDate);
  return {
    unlimitedDates: !has,
    startDate: row?.startDate ? toDateInput(row.startDate) : '',
    expiryDate: row?.expiryDate ? toDateInput(row.expiryDate) : '',
  };
}

/** Limit of a row -> { unlimited, text }. */
export function limitToForm(value) {
  return value === null || value === undefined
    ? { unlimited: true, text: '' }
    : { unlimited: false, text: String(value) };
}

// ---- creator payouts ----

/** credits = amount / creditValue, half-up to 2 decimals (07 §2); null when the value is unknown. */
export function payoutCredits(amount, creditValue) {
  const a = Number(amount);
  const v = Number(creditValue);
  if (!Number.isFinite(a) || !Number.isFinite(v) || v <= 0) return null;
  return Math.round((a / v + Number.EPSILON) * 100) / 100;
}

/** Amount text -> { value } or { error: REQUIRED | INVALID | EXCEEDS_AVAILABLE | NOTHING_AVAILABLE }. */
export function checkPayoutAmount(raw, available, exponent = 2) {
  const value = parseMoney(text(raw), exponent);
  if (value === null) return { error: 'REQUIRED' };
  if (Number.isNaN(value) || value <= 0) return { error: 'INVALID' };
  if (!(Number(available) > 0)) return { error: 'NOTHING_AVAILABLE' };
  if (value > Number(available)) return { error: 'EXCEEDS_AVAILABLE' };
  return { value };
}

/**
 * Payout form -> { errors } (field -> CODE; `actions` + `actions.<i>.<key>`) or { body }.
 * form: amount (text), method, note, actions (editor rows); `available`; `servers` = platform server
 * rows or null; `hasAccount` = false marks the CREDIT method as unusable (the creator has no account).
 */
export function buildPayoutBody(form, { available, servers = null, exponent = 2 } = {}) {
  const errors = {};
  const amount = checkPayoutAmount(form.amount, available, exponent);
  if (amount.error) errors.amount = amount.error;
  const method = PAYOUT_METHODS.includes(form.method) ? form.method : null;
  if (!method) errors.method = 'INVALID';
  if (text(form.note).length > NOTE_MAX) errors.note = 'TOO_LONG';

  let actions = null;
  if (method === 'ACTION') {
    const rows = Array.isArray(form.actions) ? form.actions : [];
    if (rows.length < 1) errors.actions = 'REQUIRED';
    const found = validateActions(rows, {}, servers, { phases: ['GRANT'] });
    for (const [path, code] of Object.entries(found)) errors[path] = code;
    if (!errors.actions && Object.keys(found).length === 0) actions = serializeActions(rows);
  }
  if (Object.keys(errors).length > 0) return { errors };

  const body = { amount: amount.value, method };
  if (text(form.note) !== '') body.note = text(form.note);
  if (method === 'ACTION') body.actions = actions;
  return { body };
}

/** The POST of the payout modal, with the Idempotency-Key of the opening (same body = same key). */
export function buildPayoutRequest(creatorId, form, options, idempotency) {
  const { errors, body } = buildPayoutBody(form, options);
  if (errors) return { errors };
  return {
    path: `/creator-codes/${creatorId}/payouts`,
    body,
    headers: { 'Idempotency-Key': idempotencyKeyFor(idempotency, { creatorId, ...body }) },
  };
}

/**
 * What the payout modal does with a failed call() result: which field to mark, whether the
 * idempotency state starts over, and the fresh `available` the server reported.
 */
export function payoutFailure(result) {
  const code = result?.error;
  if (code === 'INVALID_PAYOUT_AMOUNT') {
    const available = Number(result.body?.available);
    return {
      field: 'amount',
      reset: false,
      available: Number.isFinite(available) ? available : null,
    };
  }
  if (code === 'CREATOR_HAS_NO_ACCOUNT') return { field: 'method', reset: false, available: null };
  // a conflict (same key, other body) needs a new key; everything else keeps it for a retry
  return { field: null, reset: code === 'IDEMPOTENCY_CONFLICT', available: null };
}

// ---- report ----

/** Client-side search of the report over creator and code (no request). */
export function filterCreators(creators, query) {
  const q = text(query).toLowerCase();
  if (q === '') return creators ?? [];
  return (creators ?? []).filter(
    (row) =>
      String(row.creator ?? '')
        .toLowerCase()
        .includes(q) ||
      String(row.code ?? '')
        .toLowerCase()
        .includes(q),
  );
}

/** Creator rows can be paid out only with PAY and a positive available balance. */
export const canPayOut = (row, mayPay) => mayPay === true && Number(row?.available) > 0;

/** Earnings state filter of the creator detail: unknown values fall back to all. */
export function normalizeEarningState(value) {
  return EARNING_STATES.includes(value) ? value : null;
}

/** Only a PENDING payout can be cancelled, and only with PAY. */
export const canCancelPayout = (payout, mayPay) => mayPay === true && payout?.state === 'PENDING';

/** Redemption list path of a kind ('coupons' | 'gifts' | 'creator-codes'). */
export function redemptionsPath(kind, id, page = 1) {
  if (!['coupons', 'gifts', 'creator-codes'].includes(kind)) return null;
  return `/${kind}/${id}/redemptions${page > 1 ? `?page=${page}` : ''}`;
}

const CODE_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

/** Random 8 character code; `random` = () => number in [0, 1) (crypto-backed in the browser). */
export function generateCode(random = defaultRandom) {
  let out = '';
  for (let i = 0; i < 8; i++) out += CODE_CHARS.charAt(Math.floor(random() * CODE_CHARS.length));
  return out;
}

function defaultRandom() {
  if (typeof crypto !== 'undefined' && crypto.getRandomValues)
    return crypto.getRandomValues(new Uint32Array(1))[0] / 2 ** 32;
  return Math.random();
}

/** Badge class of a redemption state (HELD -> APPLIED / RELEASED, 01 §3.5). */
export function redemptionBadge(state) {
  if (state === 'APPLIED') return 'text-bg-success';
  if (state === 'HELD') return 'text-bg-warning';
  return 'text-bg-secondary';
}

/** Names the payout action helper offers (08 §12): the creator is the player, no order exists. */
export const PAYOUT_VARIABLES = [
  'username',
  'uuid',
  'date',
  'time',
  'server.id',
  'server.name',
  'payout.amount',
  'payout.currency',
];
