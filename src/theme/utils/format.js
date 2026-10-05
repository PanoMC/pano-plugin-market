import { currentLanguage } from '@panomc/sdk/utils/language';

/**
 * Money, credit, date and period formatting. Amounts arrive from the backend as plain decimals
 * (MoneyUtil.toDecimal).
 */

// Track the active site language for locale-aware formatting (module-level singleton store;
// the subscription lives for the app lifetime by design).
let currentLocale;
currentLanguage.subscribe((language) => {
  currentLocale = (language && language.code) || undefined;
});

const toNumber = (amount) => {
  const n = Number(amount);
  return Number.isFinite(n) ? n : 0;
};

function formatCurrency(value, currency, digits) {
  try {
    return new Intl.NumberFormat(currentLocale, {
      style: 'currency',
      currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value);
  } catch (e) {
    return null;
  }
}

export function formatPrice(amount, settings = {}) {
  const value = toNumber(amount);
  const digits = settings.removeCents ? 0 : 2;

  if (settings.currency) {
    const text = formatCurrency(value, settings.currency, digits);
    if (text !== null) return text;
  }

  return `${settings.currencySymbol || ''}${value.toFixed(digits)}`;
}

/** Same path as formatPrice with an explicit currency (orders and quotes carry their own). */
export function formatMoney(amount, currency, { removeCents = false } = {}) {
  const value = toNumber(amount);
  const digits = removeCents ? 0 : 2;

  if (currency) {
    const text = formatCurrency(value, currency, digits);
    if (text !== null) return text;
  }

  return `${value.toFixed(digits)} ${currency || ''}`.trim();
}

/** "<number with at most 2 decimals> <creditName>". */
export function formatCredits(amount, creditName = '') {
  let text;
  try {
    text = new Intl.NumberFormat(currentLocale, { maximumFractionDigits: 2 }).format(
      toNumber(amount),
    );
  } catch (e) {
    text = String(Math.round(toNumber(amount) * 100) / 100);
  }

  return `${text} ${creditName}`.trim();
}

function formatTime(ms, options) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return '';

  try {
    return new Intl.DateTimeFormat(currentLocale, options).format(new Date(n));
  } catch (e) {
    return new Date(n).toISOString();
  }
}

export const formatDate = (ms) => formatTime(ms, { dateStyle: 'medium' });

export const formatDateTime = (ms) => formatTime(ms, { dateStyle: 'medium', timeStyle: 'short' });

/** `$_` is the plugin translator of i18n.js; keys theme.period.<UNIT> take an ICU plural on `count`. */
export function formatPeriod(unit, count, $_) {
  return $_(`theme.period.${unit}`, { values: { count: Number(count) || 0 } });
}

/** Localised country name; the code itself when unknown. */
export function countryName(code) {
  if (!code) return '';

  try {
    return new Intl.DisplayNames(currentLocale, { type: 'region' }).of(code) || code;
  } catch (e) {
    return code;
  }
}
