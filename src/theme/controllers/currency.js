// `market/currency`: the preferred display currency (14 §4.5). Storage is read lazily (`init`), never at module top level.
import { defineController } from '@panomc/plugin-kit/controller';
import {
  STORAGE_KEY,
  effectiveCurrency,
  isAdoptable,
  isCode,
  isMultiCurrency,
  needsCurrencyRefetch,
} from './_currency.js';

export default defineController({
  name: 'currency',
  version: 1,
  state: () => ({ preferred: null }),
  actions: (c) => {
    function setPreferred(code) {
      const value = isCode(code) ? code : null;
      c.set({ preferred: value });

      try {
        const storage = c.host.storage('local');
        if (value) storage.setItem(STORAGE_KEY, value);
        else storage.removeItem(STORAGE_KEY);
      } catch (e) {
        // storage unavailable (private mode / quota): the preference lives for this page only
      }
    }

    return {
      /** Loads the saved preference once on mount. */
      init() {
        let saved = null;

        try {
          saved = c.host.storage('local').getItem(STORAGE_KEY);
        } catch (e) {
          saved = null;
        }

        c.set({ preferred: isCode(saved) ? saved : null });
      },
      setPreferred,
      /** Currency to send with a request (URL, then preferred, else undefined = server default). */
      effective: (settings, urlCurrency, preferredCode = c.get().preferred) =>
        effectiveCurrency(settings, urlCurrency, preferredCode),
      /** On mount: remember a valid URL currency as the preference. */
      adoptUrl(settings, urlCurrency) {
        if (isAdoptable(settings, urlCurrency)) setPreferred(urlCurrency);
      },
      /** True when a client re-fetch with `preferred` is needed after hydration. */
      needsRefetch: (settings, preferredCode = c.get().preferred) =>
        needsCurrencyRefetch(settings, preferredCode),
      /** True when the store sells in more than one currency (the selector is shown). */
      isMulti: (settings) => isMultiCurrency(settings),
    };
  },
});
