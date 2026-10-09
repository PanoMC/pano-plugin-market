// Pure currency rules of the `market/currency` controller (14 §4.5). No state, no storage.

export const STORAGE_KEY = 'pano-plugin-market-currency';

export const isCode = (code) => typeof code === 'string' && /^[A-Z]{3}$/.test(code);

function currencyList(settings) {
  // entries are ISO codes, or objects carrying a `code`
  return Array.isArray(settings?.currencies)
    ? settings.currencies.map((c) => (typeof c === 'string' ? c : c?.code)).filter(isCode)
    : [];
}

function isOffered(settings, code) {
  return isCode(code) && currencyList(settings).includes(code);
}

/** True when the store sells in more than one currency (the selector is shown). */
export function isMultiCurrency(settings) {
  return settings?.currencyMode !== 'SINGLE' && currencyList(settings).length > 1;
}

/**
 * Currency to send with a request: URL ?currency= (if offered) -> preferred (if offered) -> undefined
 * (server default). SINGLE mode sends nothing.
 */
export function effectiveCurrency(settings, urlCurrency, preferredCode) {
  if (!settings || settings.currencyMode === 'SINGLE') return undefined;
  if (isOffered(settings, urlCurrency)) return urlCurrency;
  if (isOffered(settings, preferredCode)) return preferredCode;

  return undefined;
}

/** True when `urlCurrency` should be remembered as the preference. */
export function isAdoptable(settings, urlCurrency) {
  return settings?.currencyMode !== 'SINGLE' && isOffered(settings, urlCurrency);
}

/** True when a client re-fetch with `preferred` is needed after hydration (14 §4.5). */
export function needsCurrencyRefetch(settings, preferredCode) {
  return (
    settings?.currencyMode !== 'SINGLE' &&
    isOffered(settings, preferredCode) &&
    preferredCode !== settings.displayCurrency
  );
}
