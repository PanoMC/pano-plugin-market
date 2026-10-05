// Money, number and date helpers of the market panel (13 §3.2). Pure: no Svelte, no SDK import.
// `locale` is passed in; components use utils/locale.js (`fmt`) which supplies the current one.

const DASH = '—';

function fallbackMoney(amount, currency) {
  return `${Number(amount).toFixed(2)} ${currency ?? ''}`.trim();
}

/** `removeCents` is deliberately not applied in the panel: admins see exact amounts. */
export function formatMoney(amount, currency, locale) {
  if (amount === null || amount === undefined) return DASH;
  const value = Number(amount);
  if (!Number.isFinite(value)) return DASH;
  if (!currency) return fallbackMoney(value, '');
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(value);
  } catch {
    return fallbackMoney(value, currency);
  }
}

export function formatCredits(amount, creditName, locale) {
  if (amount === null || amount === undefined) return DASH;
  const text = new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(Number(amount));
  return creditName ? `${text} ${creditName}` : text;
}

/** The API percent is a plain number: 20 = 20 %. */
export function formatPercent(value, locale) {
  if (value === null || value === undefined) return DASH;
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(Number(value)) + '%';
}

/**
 * Typed amount -> Number rounded to 2 decimals, null for empty, NaN for anything else: negative
 * values, thousands separators, more than 2 decimals, decimals when `exponent === 0`.
 */
export function parseMoney(text, exponent = 2) {
  const value = String(text ?? '').trim();
  if (value === '') return null;
  const match = /^(\d+)(?:[.,](\d+))?$/.exec(value);
  if (!match) return NaN;
  const decimals = match[2] ?? '';
  if (decimals.length > 2) return NaN;
  if (exponent === 0 && decimals.length > 0) return NaN;
  return Math.round(Number(`${match[1]}.${decimals || '0'}`) * 100) / 100;
}

/** Digits only; null for empty; NaN when out of range. */
export function parseInteger(text, { min = -Infinity, max = Infinity } = {}) {
  const value = String(text ?? '').trim();
  if (value === '') return null;
  if (!/^\d+$/.test(value)) return NaN;
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < min || n > max) return NaN;
  return n;
}

/** `$_` is the plugin i18n function; the key is an ICU plural. */
export function formatDuration(unit, count, $_) {
  return $_(`enums.period-unit.${unit}`, { values: { count } });
}

const two = (n) => String(n).padStart(2, '0');

/** `<input type="datetime-local">` value (browser zone) -> epoch ms; empty or invalid -> null. */
export function toEpoch(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return Number.isNaN(t) ? null : t;
}

/** Epoch ms -> `YYYY-MM-DDTHH:mm` in the browser zone; null or invalid -> ''. */
export function toLocalInput(epochMs) {
  if (epochMs === null || epochMs === undefined || epochMs === '') return '';
  const d = new Date(Number(epochMs));
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}`;
}

/** `<input type="date">` value -> epoch ms at the start (or the very end) of that day, browser zone. */
export function dayToEpoch(value, endOfDay = false) {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]) - 1, Number(m[3])];
  return endOfDay ? new Date(y, mo, d, 23, 59, 59, 999).getTime() : new Date(y, mo, d).getTime();
}

/** Epoch ms -> `YYYY-MM-DD` in the browser zone for `<input type="date">`. */
export function toDateInput(epochMs) {
  if (epochMs === null || epochMs === undefined || epochMs === '') return '';
  const d = new Date(Number(epochMs));
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}
