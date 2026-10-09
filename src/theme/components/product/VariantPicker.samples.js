// Sample data of the view, pure data (doc 02 section 7).
import { PRODUCT_DETAIL, VARIANTS } from './productSampleData.js';

export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { product: PRODUCT_DETAIL, selection: { duration: '30' } } },
  select: {
    props: {
      product: { ...PRODUCT_DETAIL, variantOptions: null, variants: VARIANTS },
      variantId: 101,
    },
    label: 'Plain variant list',
  },
  error: { props: { product: PRODUCT_DETAIL, selection: {}, error: 'VARIANT_REQUIRED' } },
};
