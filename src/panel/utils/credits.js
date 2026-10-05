// Credits screens (13 §11): grant / revoke request, outcome mapping and ledger helpers. Pure.
import { idempotencyKeyFor } from './api.js';
import { parseMoney } from './format.js';

export const NOTE_MIN = 3;
export const NOTE_MAX = 255;
export const AMOUNT_MAX = 1_000_000;

/** Every credit transaction type (07 §4) in display order. */
export const CREDIT_TX_TYPES = [
  'TOPUP',
  'GRANT',
  'REVOKE',
  'HOLD',
  'CAPTURE',
  'RELEASE',
  'REFUND',
  'CASHBACK',
  'CASHBACK_REVERSAL',
  'GIFT',
  'ACTION',
  'ACTION_REVERSAL',
  'CREATOR_PAYOUT',
  'EXTERNAL_IN',
  'EXTERNAL_OUT',
];

// Direction of a type for the user account: +1 credits it, -1 debits it, 0 does not touch it.
const DIRECTION = {
  TOPUP: 1,
  GRANT: 1,
  RELEASE: 1,
  REFUND: 1,
  CASHBACK: 1,
  GIFT: 1,
  ACTION: 1,
  CREATOR_PAYOUT: 1,
  EXTERNAL_IN: 1,
  REVOKE: -1,
  HOLD: -1,
  CASHBACK_REVERSAL: -1,
  ACTION_REVERSAL: -1,
  EXTERNAL_OUT: -1,
  CAPTURE: 0,
};

/** Trimmed note length 3-255: null when valid, else REQUIRED | TOO_SHORT | TOO_LONG. */
export function noteError(note) {
  const length = String(note ?? '').trim().length;
  if (length === 0) return 'REQUIRED';
  if (length < NOTE_MIN) return 'TOO_SHORT';
  if (length > NOTE_MAX) return 'TOO_LONG';
  return null;
}

/** Amount text -> { value } or { error: REQUIRED | INVALID | TOO_LARGE }; > 0, <= 1 000 000, 2 decimals. */
export function amountCheck(text) {
  const value = parseMoney(String(text ?? ''), 2);
  if (value === null) return { error: 'REQUIRED' };
  if (Number.isNaN(value) || value <= 0) return { error: 'INVALID' };
  if (value > AMOUNT_MAX) return { error: 'TOO_LARGE' };
  return { value };
}

/** Field validation only (no key is generated, safe during render): `{ error }` or `{ amount }`. */
export function validateAdjust(form) {
  const error = {};
  if (!Number.isInteger(form.userId) || form.userId <= 0) error.user = 'UNKNOWN';
  const amount = amountCheck(form.amount);
  if (amount.error) error.amount = amount.error;
  const note = noteError(form.note);
  if (note) error.note = note;
  if (Object.keys(error).length > 0) return { error };
  return { amount: amount.value };
}

/**
 * The POST of the modal. `form` = { mode, userId, amount (text), note }. Returns `{ error: { amount?,
 * note?, user? } }` (true per invalid field, reason code in the value) or `{ path, body, headers }`.
 * The idempotency fingerprint covers the mode and the user as well as the body: the same body for
 * another user or the other direction must never replay the first response.
 */
export function buildAdjustRequest(form, idempotency) {
  const { error, amount } = validateAdjust(form);
  if (error) return { error };

  const body = { amount, note: String(form.note).trim() };
  const mode = form.mode === 'revoke' ? 'revoke' : 'grant';
  const fingerprint = { mode, userId: form.userId, ...body };
  return {
    path: `/credits/accounts/${form.userId}/${mode}`,
    body,
    headers: { 'Idempotency-Key': idempotencyKeyFor(idempotency, fingerprint) },
  };
}

/**
 * What the modal does with the `call()` result. kinds: done (toast key), shortfall ({ taken, shortfall }),
 * nothing (a revoke that found nothing to take), invalidAmount, error. `reset`: start the idempotency
 * state over (success, conflict); a network error keeps it so a retry replays the same key.
 */
export function adjustOutcome(mode, requested, result) {
  if (!result.ok) {
    if (result.error === 'INVALID_CREDIT_AMOUNT') return { kind: 'invalidAmount', reset: false };
    return { kind: 'error', reset: result.error === 'IDEMPOTENCY_CONFLICT' };
  }
  const shortfall = Number(result.body?.shortfall) || 0;
  if (mode === 'revoke' && shortfall > 0) {
    const taken = Math.max(0, Math.round((requested - shortfall) * 100) / 100);
    if (taken === 0) return { kind: 'nothing', reset: true, taken, shortfall };
    return { kind: 'shortfall', reset: true, taken, shortfall };
  }
  return {
    kind: 'done',
    reset: true,
    toast: mode === 'revoke' ? 'toast-revoked' : 'toast-granted',
  };
}

/** +1 | -1 | 0 for a ledger row. Entries carry a signed amount; transactions only a type. */
export function direction(row) {
  if (typeof row.balanceAfter === 'number' || row.amount < 0) return Math.sign(row.amount) || 0;
  return DIRECTION[row.type] ?? Math.sign(row.amount);
}

export function creditBadgeClass(row) {
  const d = direction(row);
  if (d > 0) return 'text-bg-success';
  if (d < 0) return 'text-bg-danger';
  return 'text-bg-secondary';
}

/** "+12.5" / "-3" / "7" in credits (no unit); `format` formats the absolute value. */
export function signedAmount(row, format) {
  const d = direction(row);
  const text = format(Math.abs(row.amount));
  if (d > 0) return `+${text}`;
  if (d < 0) return `−${text}`;
  return text;
}

/** Account value in money: balance x credit value, two decimals. */
export function creditsValue(balance, creditValue) {
  return Math.round(Number(balance) * Number(creditValue) * 100) / 100;
}

/** Transaction filter params of the URL: `type` is csv, unknown types are dropped. */
export function normalizeTypes(csv) {
  if (!csv) return [];
  return [...new Set(String(csv).split(','))].filter((t) => CREDIT_TX_TYPES.includes(t));
}

export function toggleType(selected, type) {
  return selected.includes(type) ? selected.filter((t) => t !== type) : [...selected, type];
}

/** URL params of the transactions tab; empty values are omitted. */
export function transactionParams({
  types = [],
  userId = '',
  orderId = '',
  from = null,
  to = null,
}) {
  const id = (v) => (/^\d+$/.test(String(v ?? '').trim()) ? String(v).trim() : null);
  return {
    section: 'transactions',
    type: types.length ? types.join(',') : null,
    userId: id(userId),
    orderId: id(orderId),
    from: from ?? null,
    to: to ?? null,
  };
}
