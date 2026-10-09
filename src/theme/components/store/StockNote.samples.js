// Sample data of the view, pure data (doc 02 section 7).
import { PRODUCT } from './storeSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { product: { ...PRODUCT, stock: 4 } } },
  empty: { props: { product: PRODUCT }, label: 'Unlimited stock: nothing is drawn' },
};
