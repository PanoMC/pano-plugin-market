// `market/product`: the load of a product page (`/store/[slug]`). Instance scope, `load` only. Params: `slug` (the route
// param) and `url`, see PageLoadParams in ./types.js. A missing product resolves `{ notFound: true }`.
import { defineController } from '@panomc/plugin-kit/controller';
import { createApi } from '../lib/api.js';
import { resolveProductLoad, resolveSlug } from '../components/product/productModel.js';
import { parseCurrency } from '../lib/storeFilter.js';
import { pageUrl } from './_page.js';

const SETTINGS_PATH = '/store';

export default defineController({
  name: 'product',
  version: 1,
  scope: 'instance',
  load: async ({ host, params }) => {
    const api = createApi(host);
    const url = pageUrl(host, params);
    const slug = resolveSlug(params?.slug, host.feature('decoded-route-params'));
    const currency = parseCurrency(url.searchParams);

    const [res, settingsRes] = await Promise.all([
      api.call('GET', `/products/${encodeURIComponent(slug)}`, { query: { currency } }),
      api.call('GET', SETTINGS_PATH),
    ]);

    const result = resolveProductLoad({
      res,
      settings: settingsRes.ok && settingsRes.settings ? settingsRes.settings : null,
      slug,
      origin: url.origin,
      variantParam: url.searchParams.get('variant'),
      features: {
        meta: host.feature('page-meta'),
        titleOptions: host.feature('page-title-options'),
      },
    });

    return result;
  },
});
