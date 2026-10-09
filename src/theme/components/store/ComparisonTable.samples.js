// Sample data of the view, pure data (doc 02 section 7).
import { COMPARISON, PRODUCT_MAP, SETTINGS } from './storeSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { comparison: COMPARISON, productMap: PRODUCT_MAP, settings: SETTINGS } },
  empty: {
    props: {
      comparison: { ...COMPARISON, features: [] },
      productMap: PRODUCT_MAP,
      settings: SETTINGS,
    },
  },
};
