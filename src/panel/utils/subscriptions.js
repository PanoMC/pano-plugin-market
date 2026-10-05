// Pure rules of the subscription list, detail and CancelSubscriptionModal (13 §14). No Svelte, no SDK import.
import { can } from './permissions.js';

/** URL names forwarded to GET /subscriptions (page is handled by loadList). */
export const SUBSCRIPTION_PARAMS = ['status', 'search'];

export const ENDED_STATUSES = ['CANCELLED', 'EXPIRED', 'COMPLETED'];

export const STATUS_TABS = [
  { key: 'all', value: null },
  { key: 'active', value: 'ACTIVE' },
  { key: 'past-due', value: 'PAST_DUE' },
  { key: 'cancelled', value: ENDED_STATUSES.join(',') },
];

/** Statuses the Cancel action is offered for. */
export const CANCELLABLE_STATUSES = ['ACTIVE', 'PAST_DUE', 'PAUSED', 'PENDING'];

export const REASON_MAX = 255;

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

export function normalizeFilters(filters = {}) {
  const out = {};
  for (const name of SUBSCRIPTION_PARAMS)
    out[name] = present(filters?.[name]) ? String(filters[name]) : '';
  return out;
}

/** Key of the status tab matching `status` (csv order is not significant); null = no tab matches. */
export function activeTab(status) {
  if (!present(status)) return 'all';
  const wanted = String(status).split(',').filter(Boolean).sort().join(',');
  const tab = STATUS_TABS.find(
    (t) => t.value !== null && t.value.split(',').sort().join(',') === wanted,
  );
  return tab?.key ?? null;
}

export function listParams(filters, overrides = {}) {
  const merged = { ...normalizeFilters(filters), ...overrides };
  return Object.fromEntries(Object.entries(merged).filter(([, value]) => present(value)));
}

export const isCancellable = (subscription) => CANCELLABLE_STATUSES.includes(subscription?.status);

/** Retry Charge: merchant-initiated billing that is past due (13 §14). */
export const canRetry = (subscription) =>
  subscription?.mode === 'MERCHANT' && subscription?.status === 'PAST_DUE';

/** Menu of a row / the detail page: 'view' always (list only), 'cancel' / 'retry' need PAY. */
export function actionsFor(subscription, user) {
  const actions = [];
  if (can(user, 'PAY')) {
    if (isCancellable(subscription)) actions.push('cancel');
    if (canRetry(subscription)) actions.push('retry');
  }
  return actions;
}

/** Form of CancelSubscriptionModal -> { ok, errors? }. `timing` = 'period-end' | 'now'. */
export function validateCancel(form) {
  const errors = {};
  if (String(form?.reason ?? '').trim().length > REASON_MAX) errors.reason = 'TOO_LONG';
  if (form?.timing !== 'period-end' && form?.timing !== 'now') errors.timing = 'INVALID';
  return Object.keys(errors).length ? { ok: false, errors } : { ok: true };
}

/** Body of POST /subscriptions/:id/cancel. */
export function buildCancelBody(form) {
  const body = { atPeriodEnd: form.timing !== 'now' };
  const reason = String(form.reason ?? '').trim();
  if (reason !== '') body.reason = reason;
  return body;
}

/** Price cell: formatted amount plus the billing interval ("$5.00 / 1 month"). */
export function priceText(subscription, money, duration) {
  const amount = money(subscription.price, subscription.currency);
  if (!subscription.intervalUnit || !subscription.intervalCount) return amount;
  return `${amount} / ${duration(subscription.intervalUnit, subscription.intervalCount)}`;
}

/** "3 / 12" or "3" (no cap). */
export function cycleText(subscription) {
  const cycles = subscription?.cycleCount ?? 0;
  return subscription?.maxCycles ? `${cycles} / ${subscription.maxCycles}` : String(cycles);
}

/** Method cell: provider id plus the stored method label ("stripe - Visa 4242"). */
export function methodText(subscription) {
  return [subscription?.providerId, subscription?.storedMethodLabel].filter(present).join(' - ');
}

/** Badge class of a renewal row status (PENDING, PAID, FAILED, SKIPPED; 01 §10.2). */
export function renewalBadge(status) {
  if (status === 'PAID') return 'text-bg-success';
  if (status === 'PENDING') return 'text-bg-warning';
  if (status === 'FAILED') return 'text-bg-danger';
  return 'text-bg-secondary';
}
