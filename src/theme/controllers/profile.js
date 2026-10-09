// `market/profile`, eager: the `me/summary` of the signed-in user (14 §5 rows 9-10, §12.1), loaded once per signed-in user in the
// browser. The nav items and the dropdown entry stay in register.js (they need the host's `ui`) and read this state.
import { defineController } from '@panomc/plugin-kit/controller';
import { readSummary } from '../lib/profileModel.js';

const SUMMARY_PATH = '/me/summary';

const userKey = (u) => (u ? `${u.id ?? ''}:${u.username ?? ''}` : '');

export default defineController({
  name: 'profile',
  version: 1,
  eager: true,
  state: () => ({ summary: null }),
  actions: (c) => {
    let loadedKey = null;
    let loader = null;

    const load = () => (loader ? loader() : c.use('api').actions.call('GET', SUMMARY_PATH));

    /** Asks `me/summary` for the current user (once per user unless `force`). Does nothing on the server. */
    async function refresh({ force = false } = {}) {
      if (!c.host.browser) return;

      const current = c.host.session().user;
      const key = userKey(current);

      if (!current) {
        // signed out: forget the previous user's visibility and credit badge
        if (loadedKey !== null) {
          loadedKey = null;
          c.set({ summary: null });
        }

        return;
      }

      if (key === loadedKey && !force) return;
      loadedKey = key;

      const res = await load();

      // the user changed while the request was running: that user's own refresh covers it
      if (userKey(c.host.session().user) !== key) return;

      const summary = readSummary(res);
      if (!summary) {
        // allow another try on the next page / session change
        loadedKey = null;
        return;
      }

      c.set({ summary });
    }

    /** Forgets the loaded summary. */
    function reset() {
      loadedKey = null;
      loader = null;
      c.set({ summary: null });
    }

    /** Test seam: replaces the request (null restores the real one). */
    function setLoader(fn) {
      loader = typeof fn === 'function' ? fn : null;
    }

    return { refresh, reset, setLoader };
  },
  // only a host with the profile-nav feature shows the summary; others use the profile block, which asks by itself
  start: ({ host, actions }) =>
    host.onSession(() => {
      if (host.feature('profile-nav')) actions.refresh();
    }),
});
