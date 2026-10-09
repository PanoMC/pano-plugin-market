// Sample data of the view, pure data (doc 02 section 7).
import { BUNDLE_ITEMS } from './productSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { items: BUNDLE_ITEMS } },
  empty: { props: { items: [] } },
};
