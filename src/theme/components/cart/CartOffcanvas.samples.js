import { cartWithQuote, emptyCart, settings } from '../checkout/sampleData.js';

const settingsState = { 'market/settings': { settings } };

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: {}, controllers: { 'market/cart': cartWithQuote, ...settingsState } },
  empty: { props: {}, controllers: { 'market/cart': emptyCart, ...settingsState } },
  loading: {
    props: {},
    controllers: {
      'market/cart': { ...emptyCart, mode: 'NONE', status: 'LOADING' },
      ...settingsState,
    },
  },
  error: {
    props: {},
    controllers: {
      'market/cart': { ...emptyCart, status: 'ERROR', error: 'NETWORK' },
      ...settingsState,
    },
  },
};
