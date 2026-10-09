// Sample data of the view, pure data (doc 02 section 7).
import { SETTINGS } from './storeSampleData.js';

export const notApplicable = ['empty', 'error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { settings: SETTINGS, search: '', sort: 'priority', currency: 'USD' } },
  searching: {
    props: { settings: SETTINGS, search: 'rank', sort: 'price-asc', currency: 'EUR' },
    label: 'With a search and another currency',
  },
  single: {
    props: { settings: { ...SETTINGS, currencies: ['USD'] }, currency: 'USD' },
    label: 'One currency: no selector',
  },
};
