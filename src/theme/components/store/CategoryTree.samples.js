// Sample data of the view, pure data (doc 02 section 7).
import { CATEGORIES } from './storeSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { categories: CATEGORIES, selected: null, totalCount: 3 } },
  selected: {
    props: { categories: CATEGORIES, selected: 11, totalCount: 3 },
    label: 'A category is selected',
  },
  empty: { props: { categories: [], selected: null, totalCount: 0 } },
};
