// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK, PRODUCT, SALE_PRODUCT, SETTINGS, SUBSCRIPTION_PRODUCT } from './storeSampleData.js';

export const notApplicable = ['empty', 'error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { product: PRODUCT, settings: SETTINGS, showVat: true } },
  sale: {
    props: { product: SALE_PRODUCT, settings: SETTINGS },
    controllers: CLOCK,
    label: 'Sale price with the list price struck out',
  },
  subscription: { props: { product: SUBSCRIPTION_PRODUCT, settings: SETTINGS } },
  free: { props: { product: { ...PRODUCT, price: 0 }, settings: SETTINGS } },
};
