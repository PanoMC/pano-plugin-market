import { currentLanguage } from '@panomc/sdk/utils/language';

/**
 * Format a plain-decimal money amount using the store's currency settings.
 * Amounts arrive from the backend as plain decimals (MoneyUtil.toDecimal).
 */

// Track the active site language for locale-aware number formatting (module-level
// singleton store; the subscription lives for the app lifetime by design).
let currentLocale;
currentLanguage.subscribe((language) => {
  currentLocale = (language && language.code) || undefined;
});

export function formatPrice(amount, settings = {}) {
  const n = Number(amount);
  const value = Number.isFinite(n) ? n : 0;
  const digits = settings.removeCents ? 0 : 2;

  if (settings.currency) {
    try {
      return new Intl.NumberFormat(currentLocale, {
        style: 'currency',
        currency: settings.currency,
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      }).format(value);
    } catch (e) {
      // Unknown currency/locale — fall through to the symbol-prefix fallback.
    }
  }

  return `${settings.currencySymbol || ''}${value.toFixed(digits)}`;
}
