// `market/widgets`: the one `/widgets` request of the sidebar widgets, the store modules and the widget elements. Instance scope,
// `load` only: no state, no actions. The answer is the payload without the transport envelope (`goals`, `topSupporters`,
// `recentBuyers`, `stats`, `sidebars`), `{}` when the request failed; it never rejects, a widget then renders nothing.
import { defineController } from '@panomc/plugin-kit/controller';
import { createApi } from '../lib/api.js';

const WIDGETS_PATH = '/widgets';
const INCLUDE = 'recentBuyers,topSupporters,goals,stats';
// Four widget views load on one page: they share the request while it is in flight and for a short time after it.
const SHARE_MS = 5000;

function strip(result) {
  if (!result || result.ok === false) return {};
  const { ok, result: status, ...rest } = result;

  return rest;
}

// Per host: a browser host lives as long as the page, a server host is made for one call, so on the server nothing is shared.
const recent = new WeakMap();

export default defineController({
  name: 'widgets',
  version: 1,
  scope: 'instance',
  load: ({ host }) => {
    const now = host.now ? host.now() : Date.now();
    const known = host && typeof host === 'object' ? recent.get(host) : null;

    if (known && now - known.at < SHARE_MS) return known.promise;

    const promise = Promise.resolve(
      createApi(host).call('GET', WIDGETS_PATH, { query: { include: INCLUDE } }),
    )
      .then(strip)
      .catch(() => ({}));

    if (host && typeof host === 'object') recent.set(host, { at: now, promise });

    return promise;
  },
});
