// `market/settings`: the last `settings` object of GET /store (14 §4.4). Pages that already fetched it store it here; `ensure`
// fetches it once per page life otherwise.
import { defineController } from '@panomc/plugin-kit/controller';

const SETTINGS_PATH = '/store';

export default defineController({
  name: 'settings',
  version: 1,
  state: () => ({ settings: null }),
  actions: (c) => {
    let inflight = null;

    /** Stores the settings a page already fetched. A no-op on the server (nothing request-bound is ever kept there). */
    function set(settings) {
      if (!c.host.browser || !settings) return;

      c.set({ settings });
    }

    /**
     * Settings of the store: the stored value, else one fetch per page life. On the server it always fetches and never
     * writes the state. Resolves to null when the fetch failed. `event` is accepted for the old signature; a server
     * controller is created for its request (`controllers.use(name, { event })`), so its host already carries it.
     */
    async function ensure(event) {
      const browserSide = c.host.browser;

      if (browserSide) {
        const current = c.get().settings;
        if (current) return current;
        if (inflight) return inflight;
      }

      const run = (async () => {
        const res = await c.use('api').actions.call('GET', SETTINGS_PATH, { event });
        if (!res.ok || !res.settings) return null;

        if (browserSide) c.set({ settings: res.settings });

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

    return { set, ensure };
  },
});
