// Profile navigation (14 §5 rows 9-10, §12.1): the account dropdown entry, the four profile-nav link items and the
// `me/summary` that decides which of them are visible. The summary is loaded once per signed-in user in the browser
// (the nav items are pushed again with `hidden` / the credit badge); a failed summary leaves only the purchases link.
import { get, writable } from 'svelte/store';
import { dropdownItem, navItems, readSummary } from '../lib/profileModel.js';
import { call } from '../utils/api.js';
import { formatCredits } from '../utils/format.js';
import { has } from '../utils/host.js';
import { onSessionInit, user } from './session.js';

/** The last `me/summary` of the signed-in user (null: unknown, signed out or failed). */
export const profileSummary = writable(null);

export const SUMMARY_PATH = '/api/market/me/summary';

let pano = null;
let loadedKey = null;
let unsubscribeInit = null;
let loader = () => call('GET', SUMMARY_PATH);

/** Test seam: replaces the request (null restores the real one). */
export function setSummaryLoader(fn) {
  loader = typeof fn === 'function' ? fn : () => call('GET', SUMMARY_PATH);
}

const userKey = (u) => (u ? `${u.id ?? ''}:${u.username ?? ''}` : '');
const badgeNumber = (balance) => formatCredits(balance, '');

/** Pushes the four link items for `summary` (the engine de-duplicates by id, the last push wins). */
export function pushNavItems(host, summary) {
  const items = navItems(summary, badgeNumber);

  host.ui.profile.nav.edit((slot) => {
    slot.push(...items);

    return slot;
  });
}

/** Item 9: the account dropdown entry (always shown for a signed-in user, the dropdown itself is account only). */
export function registerDropdown(host) {
  host.ui.nav.profileDropdown.edit((items) => {
    items.push(dropdownItem());

    return items;
  });
}

/** Asks `me/summary` for the current user (once per user unless `force`), then refreshes the nav items. */
export async function refreshSummary({ force = false } = {}) {
  if (typeof window === 'undefined') return;

  const current = get(user);
  const key = userKey(current);

  if (!current) {
    // signed out: forget the previous user's visibility and credit badge
    if (loadedKey !== null) {
      loadedKey = null;
      profileSummary.set(null);
      if (pano && has('profile-nav')) pushNavItems(pano, null);
    }

    return;
  }

  if (key === loadedKey && !force) return;
  loadedKey = key;

  const res = await loader();

  // the user changed while the request was running: that user's own refresh covers it
  if (userKey(get(user)) !== key) return;

  const summary = readSummary(res);
  if (!summary) {
    // allow another try on the next page / session change
    loadedKey = null;
    return;
  }

  profileSummary.set(summary);
  if (pano && has('profile-nav')) pushNavItems(pano, summary);
}

/** Item 10, `profile-nav` branch: initial items (purchases only) and the summary refresh hook. */
export function initProfileNav(host) {
  pano = host;
  pushNavItems(host, null);

  if (unsubscribeInit) unsubscribeInit();
  unsubscribeInit = onSessionInit(() => {
    refreshSummary();
  });
}

/** Test helper: forget all module state. */
export function resetProfileNav() {
  pano = null;
  loadedKey = null;
  setSummaryLoader(null);
  profileSummary.set(null);
  if (unsubscribeInit) unsubscribeInit();
  unsubscribeInit = null;
}
