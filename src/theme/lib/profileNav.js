// Profile navigation (14 §5 rows 9-10, §12.1): the account dropdown entry and the four profile-nav link items. The
// `me/summary` that decides which of them are visible is the state of the eager `market/profile` controller; what is here
// needs the host's `ui`, so register.js calls it (nothing in a view imports this file). The nav items are pushed again with
// `hidden` / the credit badge whenever the summary changes. A failed summary leaves only the purchases link.
import { dropdownItem, navItems } from './profileModel.js';

const PROFILE = 'market/profile';
const FORMAT = 'market/format';

/** True when the host announces `feature` (pano.features.has). */
export const hostHas = (pano, feature) =>
  typeof pano?.features?.has === 'function' && pano.features.has(feature) === true;

/** Pushes the four link items for `summary` (the engine de-duplicates by id, the last push wins). */
export function pushNavItems(pano, summary) {
  const format = pano.controllers.use(FORMAT);
  const items = navItems(summary, (balance) => format.actions.formatCredits(balance, ''));

  pano.ui.profile.nav.edit((slot) => {
    slot.push(...items);

    return slot;
  });
}

/** Item 9: the account dropdown entry (always shown for a signed-in user, the dropdown itself is account only). */
export function registerDropdown(pano) {
  pano.ui.nav.profileDropdown.edit((items) => {
    items.push(dropdownItem());

    return items;
  });
}

/**
 * Item 10, `profile-nav` branch: initial items (purchases only), then every new summary refreshes the items.
 * Returns the function that stops watching.
 */
export function initProfileNav(pano) {
  pushNavItems(pano, null);

  let first = true;
  let last = null;

  return pano.controllers.use(PROFILE).subscribe((state) => {
    const summary = state?.summary ?? null;

    if (first) {
      first = false;
      last = summary;

      return;
    }
    if (summary === last) return;

    last = summary;
    if (hostHas(pano, 'profile-nav')) pushNavItems(pano, summary);
  });
}
