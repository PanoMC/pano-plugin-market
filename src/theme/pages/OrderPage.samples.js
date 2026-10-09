// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../components/store/storeSampleData.js';
import { ORDER_PAGE } from './pageSampleData.js';

export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data: ORDER_PAGE.filled }, controllers: CLOCK, session: 'user' },
  error: { props: { data: ORDER_PAGE.error }, controllers: CLOCK },
};
