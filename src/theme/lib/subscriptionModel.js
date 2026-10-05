// Pure model of the subscription and creator profile pages (14 §12.4, §12.5). No SDK, no DOM.
import { messageKey } from './errorMap.js';
import { PLUGIN_KEY_PREFIX, profileExtras, readSummary } from './profileModel.js';
import { isSafeExternalUrl } from './paymentStart.js';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

export const SUBSCRIPTIONS_TITLE_KEY = `${PLUGIN_KEY_PREFIX}theme.profile.subscriptions.title`;
export const CREATOR_TITLE_KEY = `${PLUGIN_KEY_PREFIX}theme.profile.creator.title`;

export const SUBSCRIPTION_PATH = '/api/market/me/subscriptions';
export const CREATOR_PATH = '/api/market/me/creator';

// ---- subscription rows ------------------------------------------------------------------------------------

/** Status -> badge class (`text-bg-*` only, dark mode safe). */
const STATUS_BADGES = {
  ACTIVE: 'text-bg-success',
  PAST_DUE: 'text-bg-warning',
  PAUSED: 'text-bg-info',
  PENDING: 'text-bg-secondary',
  CANCELLED: 'text-bg-secondary',
  EXPIRED: 'text-bg-secondary',
  COMPLETED: 'text-bg-secondary',
};

export const SUBSCRIPTION_STATUSES = Object.freeze(Object.keys(STATUS_BADGES));

/** An unknown status is secondary and keeps its raw value (no key). */
export function subscriptionBadge(status) {
  const known = typeof status === 'string' && Object.hasOwn(STATUS_BADGES, status);

  return {
    className: known ? STATUS_BADGES[status] : 'text-bg-secondary',
    key: known ? `theme.status.subscription.${status}` : null,
    raw: known ? null : String(status ?? ''),
  };
}

const text = (value) => (typeof value === 'string' ? value : '');

/**
 * View of one `me/subscriptions` row. `canCancel` needs the server flag and no cancel scheduled yet; `canKeep`
 * (resume) needs the scheduled cancel. Buyers only ever cancel at period end (no immediate cancel in v1).
 */
export function subscriptionView(row) {
  const s = isObject(row) ? row : {};
  const periodEnd = Number(s.currentPeriodEnd);
  const cancelAtPeriodEnd = s.cancelAtPeriodEnd === true;
  const pastDue = s.status === 'PAST_DUE';

  return {
    id: String(s.id ?? ''),
    productName: String(s.productName ?? ''),
    status: s.status,
    badge: subscriptionBadge(s.status),
    price: s.price,
    currency: s.currency,
    intervalUnit: s.intervalUnit,
    intervalCount: Number(s.intervalCount) || 1,
    periodEnd: Number.isFinite(periodEnd) && periodEnd > 0 ? periodEnd : null,
    cancelAtPeriodEnd,
    pastDue,
    methodLabel: text(s.methodLabel),
    storedMethodLabel: text(s.storedMethodLabel),
    canCancel: s.canCancel === true && !cancelAtPeriodEnd,
    canKeep: cancelAtPeriodEnd && s.canResume !== false,
    canManage: s.canManageAtGateway === true,
    // an update of the payment method only makes sense while a renewal failed
    canUpdateMethod: s.canManageAtGateway === true && pastDue,
    renewalHref:
      typeof s.renewalOrderPublicId === 'string' && s.renewalOrderPublicId
        ? `/store/order/${encodeURIComponent(s.renewalOrderPublicId)}`
        : null,
  };
}

/** Rows of a `me/subscriptions` answer; null when it is not a usable success. */
export function readSubscriptions(res) {
  if (!res || res.ok !== true) return null;

  return (Array.isArray(res.subscriptions) ? res.subscriptions : [])
    .filter((row) => isObject(row))
    .map(subscriptionView);
}

/** Replaces the row with the same id; the list is returned unchanged when the id is not in it. */
export function replaceSubscription(list, row) {
  const next = subscriptionView(row);
  if (!next.id) return list;

  return list.map((item) => (item.id === next.id ? next : item));
}

/** Request path of a subscription action. */
export const actionPath = (id, action) =>
  `${SUBSCRIPTION_PATH}/${encodeURIComponent(String(id))}/${action}`;

/** Body of the cancel request: always at period end. */
export const CANCEL_BODY = Object.freeze({ atPeriodEnd: true });

const RELOAD_CODES = ['SUBSCRIPTION_NOT_CANCELLABLE', 'SUBSCRIPTION_NOT_RESUMABLE'];

/**
 * Outcome of `cancel`, `resume` and `portal`.
 *  REPLACE   { subscription }  swap the card
 *  REDIRECT  { url }           leave the site (only a checked http(s) URL)
 *  RELOAD    { key }           toast, then reload the list (the state changed under the buyer)
 *  ERROR     { key }           toast / alert with this text
 */
export function actionOutcome(res) {
  if (res?.ok === true) {
    if (res.action === 'REDIRECT' || (res.subscription === undefined && res.url !== undefined)) {
      return isSafeExternalUrl(res.url)
        ? { kind: 'REDIRECT', url: res.url }
        : { kind: 'ERROR', key: messageKey('GENERIC') };
    }

    return isObject(res.subscription)
      ? { kind: 'REPLACE', subscription: res.subscription }
      : { kind: 'ERROR', key: messageKey('GENERIC') };
  }

  const code = res?.code;

  // a 409 of resume answers SUBSCRIPTION_NOT_CANCELLABLE too (14 §12.4)
  if (RELOAD_CODES.includes(code)) return { kind: 'RELOAD', key: messageKey(code) };
  if (code === 'SUBSCRIPTION_NOT_MANAGEABLE') return { kind: 'ERROR', key: messageKey(code) };

  return { kind: 'ERROR', key: messageKey(code) };
}

// ---- subscriptions load ------------------------------------------------------------------------------------

/**
 * Result of the subscriptions load. `subscriptions` / `summary` are ApiResults.
 * -> `{ redirect: true }` (guest) or `{ data, pageTitle, ...extras }`.
 */
export function resolveSubscriptionsLoad({ subscriptions, summary = null, features = {} } = {}) {
  if (subscriptions?.code === 'NOT_LOGGED_IN') return { redirect: true };

  const extras = profileExtras({
    sidebar: features.sidebar === true,
    meta: features.meta === true,
  });
  const rows = readSubscriptions(subscriptions);

  return {
    data:
      rows === null
        ? { state: 'ERROR', code: subscriptions?.code || 'NETWORK', summary: readSummary(summary) }
        : { state: 'READY', subscriptions: rows, summary: readSummary(summary) },
    pageTitle: { title: SUBSCRIPTIONS_TITLE_KEY },
    ...extras,
  };
}

// ---- creator ---------------------------------------------------------------------------------------------

const CODE_BADGES = { ACTIVE: 'text-bg-success', INACTIVE: 'text-bg-secondary' };
const EARNING_BADGES = {
  PENDING: 'text-bg-warning',
  AVAILABLE: 'text-bg-info',
  PAID: 'text-bg-success',
  REVERSED: 'text-bg-secondary',
};
const PAYOUT_BADGES = {
  PENDING: 'text-bg-warning',
  PAID: 'text-bg-success',
  FAILED: 'text-bg-danger',
  CANCELLED: 'text-bg-secondary',
};

export const EARNING_STATES = Object.freeze(Object.keys(EARNING_BADGES));
export const PAYOUT_STATES = Object.freeze(Object.keys(PAYOUT_BADGES));
export const PAYOUT_METHODS = Object.freeze(['CREDIT', 'ACTION', 'MANUAL']);

function badgeOf(table, keyPrefix, value) {
  const known = typeof value === 'string' && Object.hasOwn(table, value);

  return {
    className: known ? table[value] : 'text-bg-secondary',
    key: known ? `${keyPrefix}.${value}` : null,
    raw: known ? null : String(value ?? ''),
  };
}

export const codeBadge = (status) =>
  badgeOf(CODE_BADGES, 'theme.profile.creator.code-status', status);
export const earningBadge = (state) =>
  badgeOf(EARNING_BADGES, 'theme.profile.creator.state', state);
export const payoutBadge = (state) =>
  badgeOf(PAYOUT_BADGES, 'theme.profile.creator.payout-state', state);

/** Payout method text key; an unknown method shows raw. */
export function payoutMethod(method) {
  const known = PAYOUT_METHODS.includes(method);

  return {
    key: known ? `theme.profile.creator.method.${method}` : null,
    raw: known ? null : String(method ?? ''),
  };
}

/** Row of the codes table. `discount` is `{ percent }` or `{ money }`. */
export function codeRow(code) {
  const c = isObject(code) ? code : {};

  return {
    code: String(c.code ?? ''),
    discount: c.unit === 'PERCENT' ? { percent: Number(c.discount) || 0 } : { money: c.discount },
    commissionPercent: Number(c.commissionPercent) || 0,
    usedCount: Number(c.usedCount) || 0,
    badge: codeBadge(c.status),
  };
}

export function earningRow(earning) {
  const e = isObject(earning) ? earning : {};

  return {
    orderNumber: e.orderNumber,
    amount: e.amount,
    badge: earningBadge(e.state),
    createdAt: Number(e.createdAt) || 0,
  };
}

export function payoutRow(payout) {
  const p = isObject(payout) ? payout : {};
  const paidAt = Number(p.paidAt);

  return {
    amount: p.amount,
    method: payoutMethod(p.method),
    badge: payoutBadge(p.state),
    paidAt: Number.isFinite(paidAt) && paidAt > 0 ? paidAt : null,
  };
}

const money = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);

/** The earnings part of the creator page state (also used after a client-side page change). */
export function readEarnings(res) {
  return {
    earnings: (Array.isArray(res?.earnings) ? res.earnings : []).filter(isObject).map(earningRow),
    earningCount: Number(res?.earningCount) || 0,
    totalPage: Math.max(1, Number(res?.totalPage) || 1),
  };
}

/**
 * Result of the creator load. `creator` / `summary` are ApiResults.
 * -> `{ redirect }` (guest), `{ notFound }` (not a creator), or `{ data, pageTitle, ...extras }`.
 */
export function resolveCreatorLoad({ creator, summary = null, page = 1, features = {} } = {}) {
  if (creator?.code === 'NOT_LOGGED_IN') return { redirect: true };
  if (creator?.code === 'NOT_FOUND') return { notFound: true };

  const extras = profileExtras({
    sidebar: features.sidebar === true,
    meta: features.meta === true,
  });
  const title = { title: CREATOR_TITLE_KEY };

  if (!creator || creator.ok !== true)
    return {
      data: {
        state: 'ERROR',
        code: creator?.code || 'NETWORK',
        page,
        summary: readSummary(summary),
      },
      pageTitle: title,
      ...extras,
    };

  const totals = isObject(creator.totals) ? creator.totals : {};

  return {
    data: {
      state: 'READY',
      page,
      summary: readSummary(summary),
      totals: {
        earned: money(totals.earned),
        paidOut: money(totals.paidOut),
        available: money(totals.available),
        currency: typeof totals.currency === 'string' ? totals.currency : '',
      },
      codes: (Array.isArray(creator.codes) ? creator.codes : []).filter(isObject).map(codeRow),
      payouts: (Array.isArray(creator.payouts) ? creator.payouts : [])
        .filter(isObject)
        .map(payoutRow),
      ...readEarnings(creator),
    },
    pageTitle: title,
    ...extras,
  };
}
