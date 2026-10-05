// Preferred display currency (14 §4.5). Storage is read lazily (initCurrency), never at module top level.
import { get, writable } from 'svelte/store';

export const STORAGE_KEY = 'pano-plugin-market-currency';

export const preferred = writable(null);

const isCode = (code) => typeof code === 'string' && /^[A-Z]{3}$/.test(code);

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

/** Loads the saved preference once on mount. */
export function initCurrency() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    preferred.set(isCode(saved) ? saved : null);
  } catch (e) {
    preferred.set(null);
  }
}

export function setPreferred(code) {
  const value = isCode(code) ? code : null;
  preferred.set(value);

  try {
    if (value) localStorage.setItem(STORAGE_KEY, value);
    else localStorage.removeItem(STORAGE_KEY);
  } catch (e) {
    // storage unavailable (private mode / quota): the preference lives for this page only
  }
}

/**
 * Currency to send with a request: URL ?currency= (if offered) -> preferred (if offered) -> undefined
 * (server default). SINGLE mode sends nothing.
 */
export function effectiveCurrency(settings, urlCurrency, preferredCode = get(preferred)) {
  if (!settings || settings.currencyMode === 'SINGLE') return undefined;
  if (isOffered(settings, urlCurrency)) return urlCurrency;
  if (isOffered(settings, preferredCode)) return preferredCode;

  return undefined;
}

/** On mount: remember a valid URL currency as the preference. */
export function adoptUrlCurrency(settings, urlCurrency) {
  if (settings?.currencyMode !== 'SINGLE' && isOffered(settings, urlCurrency))
    setPreferred(urlCurrency);
}

/** True when a client re-fetch with `preferred` is needed after hydration (14 §4.5). */
export function needsCurrencyRefetch(settings, preferredCode = get(preferred)) {
  return (
    settings?.currencyMode !== 'SINGLE' &&
    isOffered(settings, preferredCode) &&
    preferredCode !== settings.displayCurrency
  );
}
