// Pure helpers of the store modules and sidebar widgets (14 §13). No SDK, no clock access (callers pass `now`).
import { format, remaining, withinWindow } from '../../lib/countdown.js';

export const SIDEBAR_IDS = ['home', 'profile'];
export const WIDGET_KEYS = ['goals', 'topSupporters', 'recentBuyers', 'stats'];
export const STAT_KEYS = ['ordersToday', 'ordersTotal', 'customersTotal', 'productsTotal'];

const num = (v) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
};

/** Percent clamped to 0..100; the bar never overflows, the text keeps the real numbers. */
export function clampPercent(percent) {
  return Math.min(100, Math.max(0, num(percent)));
}

/** True when the widgets response carries a non-empty value for `key` (a missing or empty key renders nothing). */
export function hasContent(data, key) {
  const value = data?.[key];

  if (key === 'stats') return STAT_KEYS.some((k) => typeof value?.[k] === 'number');

  return Array.isArray(value) && value.length > 0;
}

/**
 * A widget inside a host sidebar renders only when the admin placed it there; without `sidebarId`
 * (the store page) the placement list does not apply.
 */
export function placementAllows(data) {
  if (!data?.sidebarId) return true;

  return Array.isArray(data.sidebars) && data.sidebars.includes(data.sidebarId);
}

export function shouldRender(data, key) {
  return placementAllows(data) && hasContent(data, key);
}

/** One goal as the template needs it. `end`: countdown text within 72 h, else the end date in ms. */
export function goalView(goal, now = 0) {
  const percent = clampPercent(goal?.percent);
  const endsAt = num(goal?.endsAt);
  let end = null;

  if (endsAt > 0) {
    end =
      now > 0 && withinWindow(endsAt, now)
        ? { kind: 'COUNTDOWN', text: format(remaining(endsAt, now)) }
        : { kind: 'DATE', ms: endsAt };
  }

  return {
    id: goal?.id,
    name: String(goal?.name ?? ''),
    description: String(goal?.description ?? ''),
    revenue: goal?.metric === 'REVENUE',
    progress: num(goal?.progress),
    target: num(goal?.target),
    currency: goal?.currency || '',
    percent,
    valueNow: Math.round(percent),
    width: `width: ${percent}%`,
    complete: num(goal?.percent) >= 100,
    end,
  };
}

/** Rank 1-3 get a trophy; the colour is a Bootstrap text utility. */
export function rankView(rank) {
  const r = num(rank);
  if (r === 1) return { icon: 'fa-solid fa-trophy', tone: 'text-warning' };
  if (r === 2) return { icon: 'fa-solid fa-trophy', tone: 'text-secondary' };
  if (r === 3) return { icon: 'fa-solid fa-trophy', tone: 'text-warning-emphasis' };

  return null;
}

export function supporterView(entry) {
  return {
    username: String(entry?.username ?? ''),
    rank: num(entry?.rank),
    trophy: rankView(entry?.rank),
    total: entry?.total === undefined || entry?.total === null ? null : num(entry.total),
  };
}

export function buyerView(entry) {
  const names = Array.isArray(entry?.productNames) ? entry.productNames.map(String) : [];
  const hasAmount = entry?.amount !== undefined && entry?.amount !== null;

  return {
    username: String(entry?.username ?? ''),
    product: names[0] ?? '',
    more: Math.max(0, names.length - 1),
    amount: hasAmount ? num(entry.amount) : null,
    currency: entry?.currency || '',
    createdAt: num(entry?.createdAt),
  };
}

/** The present keys of `stats`, in the fixed order; absent keys are skipped. */
export function statsRows(stats) {
  return STAT_KEYS.filter((key) => typeof stats?.[key] === 'number').map((key) => ({
    key,
    value: stats[key],
  }));
}

/** Which module cards the store page shows, in order, each only when its settings flag is on and it has content. */
export function storeModules(settings, widgets) {
  const flags = settings?.modules ?? {};
  const out = [];

  if (flags.goal && hasContent(widgets, 'goals')) out.push('goals');
  if (flags.topSupporters && hasContent(widgets, 'topSupporters')) out.push('topSupporters');
  if (flags.recentBuyers && hasContent(widgets, 'recentBuyers')) out.push('recentBuyers');

  return out;
}
