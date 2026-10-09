// `market/format`: money, credit, date and period formatting. No state; the locale is `host.locale()` at call time.
// Amounts arrive from the backend as plain decimals (MoneyUtil.toDecimal).
import { defineController } from '@panomc/plugin-kit/controller';

const toNumber = (amount) => {
  const n = Number(amount);
  return Number.isFinite(n) ? n : 0;
};

function formatCurrency(locale, value, currency, digits) {
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value);
  } catch (e) {
    return null;
  }
}

function formatTime(locale, ms, options) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return '';

  try {
    return new Intl.DateTimeFormat(locale, options).format(new Date(n));
  } catch (e) {
    return new Date(n).toISOString();
  }
}

export default defineController({
  name: 'format',
  version: 1,
  actions: ({ host }) => {
    const locale = () => host.locale() || undefined;

    function formatPrice(amount, settings = {}) {
      const value = toNumber(amount);
      const digits = settings.removeCents ? 0 : 2;

      if (settings.currency) {
        const text = formatCurrency(locale(), value, settings.currency, digits);
        if (text !== null) return text;
      }

      return `${settings.currencySymbol || ''}${value.toFixed(digits)}`;
    }

    /** Same path as formatPrice with an explicit currency (orders and quotes carry their own). */
    function formatMoney(amount, currency, { removeCents = false } = {}) {
      const value = toNumber(amount);
      const digits = removeCents ? 0 : 2;

      if (currency) {
        const text = formatCurrency(locale(), value, currency, digits);
        if (text !== null) return text;
      }

      return `${value.toFixed(digits)} ${currency || ''}`.trim();
    }

    /** "<number with at most 2 decimals> <creditName>". */
    function formatCredits(amount, creditName = '') {
      let text;
      try {
        text = new Intl.NumberFormat(locale(), { maximumFractionDigits: 2 }).format(
          toNumber(amount),
        );
      } catch (e) {
        text = String(Math.round(toNumber(amount) * 100) / 100);
      }

      return `${text} ${creditName}`.trim();
    }

    const formatDate = (ms) => formatTime(locale(), ms, { dateStyle: 'medium' });

    const formatDateTime = (ms) =>
      formatTime(locale(), ms, { dateStyle: 'medium', timeStyle: 'short' });

    /** `$_` is the plugin translator; keys theme.period.<UNIT> take an ICU plural on `count`. */
    function formatPeriod(unit, count, $_) {
      return $_(`theme.period.${unit}`, { values: { count: Number(count) || 0 } });
    }

    /** Localised country name; the code itself when unknown. */
    function countryName(code) {
      if (!code) return '';

      try {
        return new Intl.DisplayNames(locale(), { type: 'region' }).of(code) || code;
      } catch (e) {
        return code;
      }
    }

    return {
      formatPrice,
      formatMoney,
      formatCredits,
      formatDate,
      formatDateTime,
      formatPeriod,
      countryName,
    };
  },
});
