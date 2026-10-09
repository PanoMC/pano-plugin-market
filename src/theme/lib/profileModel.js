// Pure model of the profile pages (14 §12.1-12.3): navigation items, purchases / credits load results, order rows,
// gift redemption, the free-amount top-up and the credit ledger. No SDK, no DOM: unit-tested.
import { readList } from './api-result.js';
import { creditAmountKey, messageKey, reasonKey } from './errorMap.js';
import { waitSeconds } from './paymentPanel.js';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

export const PLUGIN_KEY_PREFIX = 'plugins.pano-plugin-market.';

// ---- navigation (14 §5 rows 9-10, §12.1) --------------------------------------------------------------------

/** The four profile links in the order of the pill / block navigation. `priority` is the profile-nav priority. */
export const PROFILE_LINKS = Object.freeze([
  {
    id: 'purchases',
    navId: 'market-purchases',
    priority: 50,
    href: '/profile/purchases',
    icon: 'fa-solid fa-bag-shopping',
    key: 'theme.profile.nav.purchases',
  },
  {
    id: 'credits',
    navId: 'market-credits',
    priority: 49,
    href: '/profile/credits',
    icon: 'fa-solid fa-coins',
    key: 'theme.profile.nav.credits',
  },
  {
    id: 'subscriptions',
    navId: 'market-subscriptions',
    priority: 48,
    href: '/profile/subscriptions',
    icon: 'fa-solid fa-rotate',
    key: 'theme.profile.nav.subscriptions',
  },
  {
    id: 'creator',
    navId: 'market-creator',
    priority: 47,
    href: '/profile/creator',
    icon: 'fa-solid fa-bullhorn',
    key: 'theme.profile.nav.creator',
  },
]);

/** Purchases is always shown; the others only when `me/summary` says so. A missing / failed summary = purchases only. */
export function linkVisibility(summary) {
  const s = isObject(summary) ? summary : {};

  return {
    purchases: true,
    credits: s.creditsEnabled === true,
    subscriptions: Number(s.subscriptionCount) > 0,
    creator: s.isCreator === true,
  };
}

/** The credit balance as a badge text (a bare number); null without a usable balance. */
export function creditBadge(summary, formatNumber) {
  if (!isObject(summary) || summary.creditsEnabled !== true) return null;

  const balance = Number(summary.creditBalance);

  return Number.isFinite(balance) ? formatNumber(balance) : null;
}

/**
 * The four link items in the host's profile-nav shape (15 §4.6). `hidden` is set only where it is true, the credits
 * item carries the balance as `props.badge` while it is visible.
 */
export function navItems(summary, formatNumber = (n) => String(n)) {
  const visible = linkVisibility(summary);
  const badge = creditBadge(summary, formatNumber);

  return PROFILE_LINKS.map((link) => {
    const props = {
      href: link.href,
      text: `${PLUGIN_KEY_PREFIX}${link.key}`,
      icon: link.icon,
    };

    if (link.id === 'credits' && visible.credits && badge !== null) props.badge = badge;

    const item = { id: link.navId, priority: link.priority, props };
    if (!visible[link.id]) item.hidden = true;

    return item;
  });
}

/** The single purchases entry of the account dropdown (sits between settings 70 and logout 10). */
export function dropdownItem() {
  const purchases = PROFILE_LINKS[0];

  return {
    id: purchases.navId,
    priority: 60,
    props: {
      href: purchases.href,
      text: `${PLUGIN_KEY_PREFIX}${purchases.key}`,
      icon: purchases.icon,
    },
  };
}

/** Links the pill navigation and the profile block show: `{ id, href, icon, key, badge }` for the visible ones. */
export function visibleLinks(summary, formatNumber = (n) => String(n)) {
  const visible = linkVisibility(summary);
  const badge = creditBadge(summary, formatNumber);

  return PROFILE_LINKS.filter((link) => visible[link.id]).map((link) => ({
    id: link.id,
    href: link.href,
    icon: link.icon,
    key: link.key,
    badge: link.id === 'credits' ? badge : null,
  }));
}

/** Reads a `me/summary` answer; null when it is not a usable success. */
export function readSummary(res) {
  if (!res || res.ok !== true) return null;

  return {
    creditsEnabled: res.creditsEnabled === true,
    creditBalance: Number.isFinite(Number(res.creditBalance)) ? Number(res.creditBalance) : 0,
    creditName: typeof res.creditName === 'string' ? res.creditName : '',
    activeSubscriptionCount: Number(res.activeSubscriptionCount) || 0,
    subscriptionCount: Number(res.subscriptionCount) || 0,
    isCreator: res.isCreator === true,
  };
}

// ---- page load results ------------------------------------------------------------------------------------

export const PURCHASES_TITLE_KEY = `${PLUGIN_KEY_PREFIX}theme.profile.purchases.title`;
export const CREDITS_TITLE_KEY = `${PLUGIN_KEY_PREFIX}theme.profile.credits.title`;

export const ORDER_FILTERS = Object.freeze(['COMPLETED', 'PENDING', 'REFUNDED', 'CANCELLED']);

const positiveInt = (value) => {
  if (value == null || !/^\d+$/.test(String(value).trim())) return null;
  const n = Number(value);

  return Number.isSafeInteger(n) && n >= 1 ? n : null;
};

/** `?page=` (positive integer, else 1) and `?status=` (one of ORDER_FILTERS, else '' = all). */
export function parseListQuery(params) {
  const get = (name) => (params && typeof params.get === 'function' ? params.get(name) : null);
  const status = get('status');

  return {
    page: positiveInt(get('page')) ?? 1,
    status: ORDER_FILTERS.includes(status) ? status : '',
  };
}

/** Query string (without '?') of the address bar for a list state; defaults are left out. */
export function listSearch({ page = 1, status = '' } = {}) {
  const parts = [];

  if (status) parts.push(`status=${encodeURIComponent(status)}`);
  if (page > 1) parts.push(`page=${page}`);

  return parts.join('&');
}

/** Query of `GET me/orders`. */
export const ordersQuery = ({ page = 1, status = '' } = {}) => ({
  page,
  status: status || undefined,
});

/** Page-level extras of every profile load (sidebar only for a host that knows page-sidebar-id; robots always). */
export function profileExtras({ sidebar = false, meta = false } = {}) {
  const extra = {};

  if (sidebar) extra.sidebar = 'profile';
  if (meta) extra.meta = { robots: 'noindex,nofollow' };

  return extra;
}

const orderList = (res) => {
  if (!(res && res.ok === true))
    return {
      state: 'ERROR',
      code: res?.code || 'NETWORK',
      orders: [],
      orderCount: 0,
      totalPages: 1,
    };

  const list = readList(res);

  return {
    state: 'READY',
    orders: list.items,
    orderCount: list.totalItems,
    totalPages: list.totalPages,
  };
};

/** The order list part of the purchases page state (also used after a client-side fetch). */
export const readOrders = orderList;

/**
 * Result of the purchases load. `orders` / `entitlements` / `summary` are ApiResults (summary is only asked for in the
 * pill mode). -> `{ redirect: true }` (guest) or `{ data, pageTitle, ...extras }`.
 */
export function resolvePurchasesLoad({
  orders,
  entitlements,
  summary = null,
  filter,
  settings = null,
  features = {},
} = {}) {
  if (orders?.code === 'NOT_LOGGED_IN' || entitlements?.code === 'NOT_LOGGED_IN')
    return { redirect: true };

  return {
    data: {
      state: 'READY',
      filter: filter ?? { page: 1, status: '' },
      orders: orderList(orders),
      entitlements:
        entitlements?.ok === true && Array.isArray(entitlements.items) ? entitlements.items : [],
      entitlementsFailed: !(entitlements?.ok === true),
      summary: readSummary(summary),
      settings: settings || {},
    },
    pageTitle: { title: PURCHASES_TITLE_KEY },
    ...profileExtras({ sidebar: features.sidebar === true, meta: features.meta === true }),
  };
}

/** The credits load needs the store settings first: is there a credit system at all, is the top-up on? */
export function creditsGate(settings) {
  if (!isObject(settings)) return { state: 'ERROR' };
  if (settings.creditsEnabled === false) return { state: 'NOT_FOUND' };

  return { state: 'OK', topUp: settings.creditTopUpEnabled === true };
}

/**
 * Result of the credits load. `credits` / `config` / `packs` / `summary` are ApiResults, `settings` the store settings.
 * -> `{ notFound }`, `{ redirect }` or `{ data, pageTitle, ...extras }`.
 */
export function resolveCreditsLoad({
  credits,
  config = null,
  packs = null,
  summary = null,
  page = 1,
  settings,
  features = {},
} = {}) {
  const gate = creditsGate(settings);

  if (credits?.code === 'NOT_LOGGED_IN') return { redirect: true };
  if (gate.state === 'NOT_FOUND' || credits?.code === 'CREDITS_DISABLED') return { notFound: true };

  const extras = profileExtras({
    sidebar: features.sidebar === true,
    meta: features.meta === true,
  });
  const title = { title: CREDITS_TITLE_KEY };

  if (gate.state === 'ERROR' || !credits || credits.ok !== true)
    return {
      data: {
        state: 'ERROR',
        code: (gate.state === 'ERROR' ? null : credits?.code) || 'NETWORK',
        page,
        summary: readSummary(summary),
      },
      pageTitle: title,
      ...extras,
    };

  const topUp = gate.topUp ? readTopUp(config) : null;

  return {
    data: {
      state: 'READY',
      page,
      settings,
      balance: Number(credits.balance) || 0,
      creditName: typeof credits.creditName === 'string' ? credits.creditName : '',
      ledger: readLedger(credits),
      topUp,
      packs:
        topUp && packs?.ok === true
          ? readList(packs).items.filter((p) => isObject(p))
          : [],
      summary: readSummary(summary),
    },
    pageTitle: title,
    ...extras,
  };
}

/** The ledger part of the credits page state. */
export function readLedger(res) {
  const list = readList(res);

  return { entries: list.items, entryCount: list.totalItems, totalPages: list.totalPages };
}

// ---- purchases: entitlements and order rows ---------------------------------------------------------------

export const DAY_MS = 24 * 3600 * 1000;
export const SOON_MS = 3 * DAY_MS;

/** One active product: permanent without `expiresAt`; `soon` (warning colour) below three days left. */
export function entitlementView(entitlement, now = 0) {
  const e = isObject(entitlement) ? entitlement : {};
  const expiresAt = Number(e.expiresAt);
  const expires = Number.isFinite(expiresAt) && expiresAt > 0 ? expiresAt : null;

  return {
    id: e.id,
    name: String(e.productName ?? ''),
    variant: e.variantName ? String(e.variantName) : '',
    permanent: expires === null,
    expiresAt: expires,
    soon: expires !== null && now > 0 && expires - now < SOON_MS,
    subscriptionId: e.subscriptionId ?? null,
  };
}

/** The first two item names, then how many more. */
export function itemsSummary(itemNames) {
  const names = (Array.isArray(itemNames) ? itemNames : []).map((n) => String(n));

  return { names: names.slice(0, 2), more: Math.max(0, names.length - 2) };
}

/** Gift marker of an order row: `received` wins over `isGift`. -> null | { kind: 'RECEIVED' } | { kind: 'SENT', username } */
export function giftMarker(order) {
  if (order?.received === true) return { kind: 'RECEIVED' };
  if (order?.isGift === true)
    return { kind: 'SENT', username: String(order.recipientUsername ?? '') };

  return null;
}

/** Row model of the orders table. */
export function orderRow(order) {
  const o = isObject(order) ? order : {};

  return {
    publicId: String(o.publicId ?? ''),
    number: o.number,
    href: `/store/order/${encodeURIComponent(String(o.publicId ?? ''))}`,
    createdAt: Number(o.createdAt) || 0,
    items: itemsSummary(o.itemNames),
    total: o.total,
    currency: o.currency,
    status: o.status,
    gift: giftMarker(o),
  };
}

/** Status -> badge. `text-bg-*` only (dark mode); an unknown status is secondary and keeps its raw value. */
const BADGES = {
  PENDING: 'text-bg-warning',
  REVIEW: 'text-bg-info',
  COMPLETED: 'text-bg-success',
  PARTIALLY_REFUNDED: 'text-bg-secondary',
  REFUNDED: 'text-bg-secondary',
  CANCELLED: 'text-bg-secondary',
  EXPIRED: 'text-bg-secondary',
  CHARGEBACK: 'text-bg-danger',
  FAILED: 'text-bg-danger',
};

export const ORDER_STATUSES = Object.freeze(Object.keys(BADGES));

export function orderBadge(status) {
  const known = typeof status === 'string' && Object.hasOwn(BADGES, status);

  return {
    className: known ? BADGES[status] : 'text-bg-secondary',
    key: known ? `theme.status.order.${status}` : null,
    raw: known ? null : String(status ?? ''),
  };
}

// ---- gift code redemption (14 §12.2) ----------------------------------------------------------------------

export const GIFT_CODE_MAX = 64;

export const GIFT_NEEDS_OPTIONS_KEY = 'theme.profile.purchases.gift-needs-options';

const NEEDS_OPTIONS = ['SERVER_REQUIRED', 'FIELD_REQUIRED'];

/** Trimmed code; '' when empty. The server compares exactly, the form only trims. */
export const normalizeGiftCode = (value) =>
  String(value ?? '')
    .trim()
    .slice(0, GIFT_CODE_MAX);

/**
 * Outcome of `POST me/gifts/redeem`.
 *  GOTO     { path }           open the zero-total order
 *  INVALID  { key }            the code field is invalid with this text
 *  LOCKED   { key, seconds }   disabled with a countdown
 *  ERROR    { key }            a general alert
 */
export function giftOutcome(res) {
  if (res?.ok === true) {
    const publicId = res.order?.publicId;

    return typeof publicId === 'string' && publicId
      ? { kind: 'GOTO', path: `/store/order/${encodeURIComponent(publicId)}` }
      : { kind: 'ERROR', key: messageKey('GENERIC') };
  }

  const code = res?.code;

  if (code === 'INVALID_GIFT_CODE') {
    if (NEEDS_OPTIONS.includes(res.reason)) return { kind: 'INVALID', key: GIFT_NEEDS_OPTIONS_KEY };

    return { kind: 'INVALID', key: reasonKey(res.reason, 'INVALID_GIFT_CODE') };
  }

  if (code === 'CODE_ATTEMPTS_LOCKED' || code === 'TOO_MANY_REQUESTS')
    return { kind: 'LOCKED', key: messageKey(code), seconds: waitSeconds(res.retryAfter) };

  return { kind: 'ERROR', key: messageKey(code) };
}

// ---- credits: top-up and ledger (14 §12.3, 07 §8) ---------------------------------------------------------

/** `creditTopUp` of checkout/config; null when the top-up is off or the block is unusable. */
export function readTopUp(config) {
  const t = config?.ok === true ? config.creditTopUp : null;

  if (!isObject(t) || t.enabled !== true) return null;

  const min = Number(t.min);
  const max = Number(t.max);
  const creditValue = Number(t.creditValue);

  return {
    freeAmount: t.freeAmount === true,
    min: Number.isFinite(min) && min > 0 ? min : 0.01,
    max: Number.isFinite(max) && max > 0 ? max : Infinity,
    creditValue: Number.isFinite(creditValue) && creditValue > 0 ? creditValue : 0,
    currency: typeof t.currency === 'string' ? t.currency : '',
  };
}

/** Credits as integer hundredths ("12.5" -> 1250); null when not a plain decimal with at most two places. */
export function toCents(text) {
  const match = /^(\d{1,12})(?:[.,](\d{1,2}))?$/.exec(String(text ?? '').trim());
  if (!match) return null;

  return Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0') || 0);
}

/**
 * Client-side check of the free amount, with the reasons of the server (07 §8.2).
 * -> `{ ok: true, amount, cents }` | `{ ok: false, reason }`; an empty input is NOT_A_NUMBER.
 */
export function validateTopUp(text, topUp) {
  if (!topUp || topUp.freeAmount !== true) return { ok: false, reason: 'TOPUP_DISABLED' };

  const cents = toCents(text);
  if (cents === null) return { ok: false, reason: 'NOT_A_NUMBER' };

  const minCents = Math.round(topUp.min * 100);
  const maxCents = Number.isFinite(topUp.max) ? Math.round(topUp.max * 100) : Infinity;

  if (cents < minCents) return { ok: false, reason: 'BELOW_MINIMUM' };
  if (cents > maxCents) return { ok: false, reason: 'ABOVE_MAXIMUM' };

  return { ok: true, amount: cents / 100, cents };
}

/** Text key of a failed top-up check (`min` / `max` are the interpolation values). */
export const topUpErrorKey = (reason) => creditAmountKey(reason);

/** Money cost of `cents` credits at `creditValue` per credit, in hundredths, at least one unit (07 §8.2). */
export function topUpCostCents(cents, creditValue) {
  if (!(cents > 0) || !(creditValue > 0)) return 0;

  return Math.max(1, Math.round(Number((cents * creditValue).toFixed(6))));
}

/** The checkout address of a validated amount: `/store/checkout?topup=12.5`. */
export const topUpHref = (cents) => `/store/checkout?topup=${cents / 100}`;

const LEDGER_TYPES = [
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

/** Every ledger type that has a `theme.profile.credits.type.<TYPE>` key (01 §7.2). */
export const LEDGER_TYPE_KEYS = Object.freeze([...LEDGER_TYPES]);

/** Type text key, or the raw value for a type this theme does not know. */
export function ledgerType(type) {
  const known = typeof type === 'string' && LEDGER_TYPES.includes(type);

  return {
    key: known ? `theme.profile.credits.type.${type}` : null,
    raw: known ? null : String(type ?? ''),
  };
}

/** Signed amount: `+` and success colour above zero, danger colour below, plain at zero. */
export function ledgerAmount(amount) {
  const n = Number(amount);
  const value = Number.isFinite(n) ? n : 0;

  return {
    value,
    sign: value > 0 ? '+' : '',
    className: value > 0 ? 'text-success' : value < 0 ? 'text-danger' : '',
  };
}

/** Row model of the ledger table. */
export function ledgerRow(entry) {
  const e = isObject(entry) ? entry : {};
  const order = typeof e.orderPublicId === 'string' && e.orderPublicId ? e.orderPublicId : null;

  return {
    id: e.id,
    createdAt: Number(e.createdAt) || 0,
    type: ledgerType(e.type),
    amount: ledgerAmount(e.amount),
    balanceAfter: Number(e.balanceAfter) || 0,
    note: typeof e.note === 'string' ? e.note : '',
    orderHref: order ? `/store/order/${encodeURIComponent(order)}` : null,
  };
}
