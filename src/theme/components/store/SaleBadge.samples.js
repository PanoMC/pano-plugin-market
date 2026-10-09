// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK, PRODUCT, SALE_PRODUCT, SETTINGS } from './storeSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { product: SALE_PRODUCT, settings: SETTINGS }, controllers: CLOCK },
  empty: { props: { product: PRODUCT, settings: SETTINGS }, label: 'No sale: nothing is drawn' },
};
