// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../components/store/storeSampleData.js';
import { STORE, STORE_DISABLED, STORE_ERROR } from './pageSampleData.js';

export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data: STORE.filled }, controllers: { ...CLOCK, 'market/cart': { count: 2 } } },
  empty: { props: { data: STORE.empty }, controllers: CLOCK },
  error: { props: { data: STORE_ERROR } },
  closed: { props: { data: STORE_DISABLED }, label: 'The store is switched off' },
};
