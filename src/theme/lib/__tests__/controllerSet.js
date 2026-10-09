// The market controllers as the build registers them (every public file of src/theme/controllers/), for the tests: the
// controllers test environment (controllerEnv.js) registers this set the way the generated entry registers `pano:controllers`.
// Order is registration order: nothing here depends on it (siblings are resolved lazily).
import format from '../../controllers/format.js';
import api from '../../controllers/api.js';
import host from '../../controllers/host.js';
import session from '../../controllers/session.js';
import settings from '../../controllers/settings.js';
import clock from '../../controllers/clock.js';
import cart from '../../controllers/cart.js';
import currency from '../../controllers/currency.js';
import checkoutDraft from '../../controllers/checkoutDraft.js';
import store from '../../controllers/store.js';
import product from '../../controllers/product.js';
import order from '../../controllers/order.js';
import checkout from '../../controllers/checkout.js';
import profile from '../../controllers/profile.js';
import widgets from '../../controllers/widgets.js';

export const PLUGIN_ID = 'pano-plugin-market';
export const NAMESPACE = 'market';

export const ALL = {
  format,
  api,
  host,
  session,
  settings,
  clock,
  cart,
  currency,
  checkoutDraft,
  store,
  product,
  order,
  checkout,
  profile,
  widgets,
};
