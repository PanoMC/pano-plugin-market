// Last `settings` object of GET /api/market/store (14 §4.4).
import { get, writable } from 'svelte/store';
import { call } from '../utils/api.js';

export const storeSettings = writable(null);

export const SETTINGS_PATH = '/api/market/store';

let inflight = null;

/** Stores the settings a page already fetched. A no-op on the server (the store is process-global there). */
export function setSettings(settings) {
  if (typeof window === 'undefined' || !settings) return;

  storeSettings.set(settings);
}

/**
 * Settings of the store: the stored value, else one fetch per page life. On the server it always
 * fetches and never writes the store. Resolves to null when the fetch failed.
 */
export async function ensureSettings(event) {
  const browserSide = typeof window !== 'undefined';

  if (browserSide) {
    const current = get(storeSettings);
    if (current) return current;
    if (inflight) return inflight;
  }

  const run = (async () => {
    const res = await call('GET', SETTINGS_PATH, { event });
    if (!res.ok || !res.settings) return null;

    if (browserSide) storeSettings.set(res.settings);

    return res.settings;
  })();

  if (browserSide) {
    inflight = run;
    run.finally(() => {
      if (inflight === run) inflight = null;
    });
  }

  return run;
}
