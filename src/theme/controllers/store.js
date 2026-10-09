// `market/store`: the load of the store front page (`/store`). Instance scope, `load` only: no state, no actions.
// Params: see PageLoadParams in ./types.js. The result is the view's `load` result; where the old load threw a redirect it
// carries `redirect: { status, location }`. The view stores the settings (`market/settings` `set`) itself.
import { defineController } from '@panomc/plugin-kit/controller';
import { createApi } from '../lib/api.js';
import { hasFilter, resolveStoreLoad, validateFilter } from '../lib/storeLoad.js';
import { listQuery, parseCurrency, parseFilter, withoutPageParam } from '../lib/storeFilter.js';
import { pageUrl } from './_page.js';

const STORE_PATH = '/store';
const LIST_PATH = '/store/products';
const WIDGETS_PATH = '/widgets';

export default defineController({
  name: 'store',
  version: 1,
  scope: 'instance',
  load: async ({ host, params }) => {
    const api = createApi(host);
    const url = pageUrl(host, params);
    const filter = parseFilter(url.searchParams);
    const currency = parseCurrency(url.searchParams);
    const fetchList = (f) => api.call('GET', LIST_PATH, { query: listQuery(f, currency) });

    const [store, widgets, firstList] = await Promise.all([
      api.call('GET', STORE_PATH, { query: { currency } }),
      api.call('GET', WIDGETS_PATH, { query: { include: 'recentBuyers,topSupporters,goals' } }),
      hasFilter(filter) ? fetchList(filter) : null,
    ]);

    let list = firstList;

    // an unknown ?category is dropped; the list then has to be asked again without it
    if (store.ok) {
      const valid = validateFilter(filter, store.categories || []);
      if (valid !== filter) list = hasFilter(valid) ? await fetchList(valid) : null;
    }

    const result = resolveStoreLoad({
      store,
      list,
      widgets,
      filter,
      origin: url.origin,
      withMeta: host.feature('page-meta'),
    });

    if (result.redirect)
      return {
        ...result,
        redirect: { status: 302, location: withoutPageParam(`${url.pathname}${url.search}`) },
      };

    return result;
  },
});
