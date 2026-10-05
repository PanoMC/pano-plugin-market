// Stats range of the overview page (13 §4.1). Pure: the range is turned into epoch milliseconds in
// the browser zone; the server buckets them in storeTimeZone (00 §9).
export const RANGES = ['7d', '30d', '90d', 'month'];
export const DEFAULT_RANGE = '30d';

const startOfDay = (date) => new Date(date.getFullYear(), date.getMonth(), date.getDate());
const endOfDay = (date) =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999);

/**
 * `7d` / `30d` / `90d` = the last N calendar days including today; `month` = the first day of the
 * current calendar month up to the end of today (browser zone). Returns `{ from, to }` in epoch ms.
 */
export function rangeToParams(range, now = Date.now()) {
  const today = new Date(now);
  const to = endOfDay(today).getTime();
  if (range === 'month') {
    return { from: new Date(today.getFullYear(), today.getMonth(), 1).getTime(), to };
  }
  const days = { '7d': 7, '30d': 30, '90d': 90 }[range] ?? 30;
  const first = new Date(today.getFullYear(), today.getMonth(), today.getDate() - (days - 1));
  return { from: startOfDay(first).getTime(), to };
}

const epoch = (value) => {
  if (typeof value !== 'string' || !/^\d{1,15}$/.test(value)) return null;
  return Number(value);
};

/**
 * URL -> `{ range, from, to }`. `?from=<ms>&to=<ms>` (both numeric, from <= to) is the custom range;
 * otherwise `?range=` when known, else the default. A half or reversed custom range is ignored.
 */
export function parseRange(searchParams, now = Date.now()) {
  const from = epoch(searchParams.get('from'));
  const to = epoch(searchParams.get('to'));
  if (from !== null && to !== null && from <= to) return { range: 'custom', from, to };
  const requested = searchParams.get('range');
  const range = RANGES.includes(requested) ? requested : DEFAULT_RANGE;
  return { range, ...rangeToParams(range, now) };
}

/** Query parameters that put a range into the URL (the default range keeps the URL clean). */
export function rangeQuery(range, from = null, to = null) {
  if (range === 'custom') return { range: null, from, to };
  return { range: range === DEFAULT_RANGE ? null : range, from: null, to: null };
}

/** Query parameters of the overview URL: the range plus `?view=chart` (table is the default). */
export function overviewQuery({ range, from = null, to = null, view = 'table' }) {
  return { ...rangeQuery(range, from, to), view: view === 'chart' ? 'chart' : null };
}
