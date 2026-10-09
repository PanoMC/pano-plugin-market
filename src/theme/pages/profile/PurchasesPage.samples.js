// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../../components/store/storeSampleData.js';
import { PURCHASES } from '../pageSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data: PURCHASES.filled }, controllers: CLOCK, session: 'user' },
  empty: { props: { data: PURCHASES.empty }, controllers: CLOCK, session: 'user' },
};
