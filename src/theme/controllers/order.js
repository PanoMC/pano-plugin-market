// `market/order`: the load of an order page (`/store/order/[id]`). Instance scope, `load` only. Params: `id` (the route param)
// and `url`, see PageLoadParams in ./types.js. A bad or unknown order resolves `{ notFound: true }`.
import { defineController } from '@panomc/plugin-kit/controller';
import { createApi } from '../lib/api.js';
import { resolveSlug } from '../components/product/productModel.js';
import { parseOrderId, parseReturnHint, resolveOrderLoad } from '../lib/orderState.js';
import { pageUrl } from './_page.js';

const SETTINGS_PATH = '/store';

export default defineController({
  name: 'order',
  version: 1,
  scope: 'instance',
  load: async ({ host, params }) => {
    const api = createApi(host);
    const url = pageUrl(host, params);
    const id = parseOrderId(resolveSlug(params?.id, host.feature('decoded-route-params')));

    if (!id) return { notFound: true };

    const locale = host.locale();

    // The SSR load never forwards the access token (14 §11.2): it asks for the limited view only; the browser
    // re-fetches with X-Order-Token after mount. A session owner is recognised by the cookie as usual.
    const [res, settingsRes] = await Promise.all([
      api.call('GET', `/orders/${encodeURIComponent(id)}`, { query: { locale } }),
      api.call('GET', SETTINGS_PATH),
    ]);

    return resolveOrderLoad({
      id,
      token: url.searchParams.get('token'),
      returnHint: parseReturnHint(url.searchParams.get('return')),
      res,
      settings: settingsRes.ok && settingsRes.settings ? settingsRes.settings : null,
      features: { meta: host.feature('page-meta') },
    });
  },
});
