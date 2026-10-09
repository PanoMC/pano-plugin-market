// Sample data of the view, pure data (doc 02 section 7).
import {
  CLOCK,
  PRODUCT,
  SALE_PRODUCT,
  SETTINGS,
  SOLD_OUT_PRODUCT,
  SUBSCRIPTION_PRODUCT,
} from './storeSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { product: PRODUCT, settings: SETTINGS },
    controllers: { ...CLOCK, 'market/cart': { count: 2 } },
  },
  empty: { props: { product: SOLD_OUT_PRODUCT, settings: SETTINGS }, label: 'Sold out' },
  sale: { props: { product: SALE_PRODUCT, settings: SETTINGS }, controllers: CLOCK },
  options: { props: { product: SUBSCRIPTION_PRODUCT, settings: SETTINGS }, label: 'Needs options' },
};
