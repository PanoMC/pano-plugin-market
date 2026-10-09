// `market/checkout`: the load of the checkout page (`/store/checkout`). Instance scope, `load` only. Params: `url`, see
// PageLoadParams in ./types.js.
import { defineController } from '@panomc/plugin-kit/controller';
import { createApi } from '../lib/api.js';
import { parseTopup, resolveCheckoutLoad } from '../lib/checkoutModel.js';
import { pageUrl } from './_page.js';

const SETTINGS_PATH = '/store';

export default defineController({
  name: 'checkout',
  version: 1,
  scope: 'instance',
  load: async ({ host, params }) => {
    const api = createApi(host);
    const url = pageUrl(host, params);
    const topup = parseTopup(url.searchParams.get('topup'));
    const locale = host.locale();

    const [res, settingsRes] = await Promise.all([
      api.call('GET', '/checkout/config', { query: { locale } }),
      api.call('GET', SETTINGS_PATH),
    ]);

    return resolveCheckoutLoad({
      res,
      settings: settingsRes.ok && settingsRes.settings ? settingsRes.settings : null,
      topup,
      features: { meta: host.feature('page-meta') },
    });
  },
});
