// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../components/store/storeSampleData.js';
import { CHECKOUT } from './pageSampleData.js';

export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { data: CHECKOUT.filled },
    controllers: { ...CLOCK, 'market/cart': { count: 2, mode: 'SERVER', status: 'IDLE' } },
    session: 'user',
  },
  empty: {
    props: { data: CHECKOUT.filled },
    controllers: { ...CLOCK, 'market/cart': { count: 0, mode: 'SERVER', status: 'IDLE' } },
    session: 'user',
    label: 'Empty cart',
  },
  error: { props: { data: CHECKOUT.error } },
};
