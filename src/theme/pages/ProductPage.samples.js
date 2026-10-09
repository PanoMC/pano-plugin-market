// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../components/store/storeSampleData.js';
import { PRODUCT_PAGE } from './pageSampleData.js';

export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { data: PRODUCT_PAGE.filled },
    controllers: { ...CLOCK, 'market/cart': { count: 2 } },
  },
  sale: { props: { data: PRODUCT_PAGE.sale }, controllers: CLOCK },
  subscription: { props: { data: PRODUCT_PAGE.subscription }, controllers: CLOCK },
  error: { props: { data: PRODUCT_PAGE.error } },
  closed: { props: { data: PRODUCT_PAGE.disabled }, label: 'The store is switched off' },
};
