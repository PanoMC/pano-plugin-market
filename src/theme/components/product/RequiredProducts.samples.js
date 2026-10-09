// Sample data of the view, pure data (doc 02 section 7).
import { REQUIRED_PRODUCTS } from './productSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { products: REQUIRED_PRODUCTS, requireOnlyOne: false } },
  anyOne: { props: { products: REQUIRED_PRODUCTS, requireOnlyOne: true } },
  empty: { props: { products: [] } },
};
