import { get } from 'svelte/store';
import { currentLanguage } from '@panomc/sdk/utils/language';
import { formatCredits, formatMoney, formatPercent } from './format.js';

/** Current UI locale, e.g. 'en-US'. Read when called, so it is safe outside components. */
export function currentLocale() {
  try {
    return get(currentLanguage)?.code ?? 'en-US';
  } catch {
    return 'en-US';
  }
}

/** Components call fmt.money(amount, currency) instead of passing a locale around. */
export const fmt = {
  money: (amount, currency) => formatMoney(amount, currency, currentLocale()),
  credits: (amount, creditName) => formatCredits(amount, creditName, currentLocale()),
  percent: (value) => formatPercent(value, currentLocale()),
};
