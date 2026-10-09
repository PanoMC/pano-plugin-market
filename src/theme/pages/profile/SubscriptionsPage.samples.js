// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../../components/store/storeSampleData.js';
import { SUBSCRIPTIONS } from '../pageSampleData.js';

export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data: SUBSCRIPTIONS.filled }, controllers: CLOCK, session: 'user' },
  empty: { props: { data: SUBSCRIPTIONS.empty }, controllers: CLOCK, session: 'user' },
  error: { props: { data: SUBSCRIPTIONS.error }, controllers: CLOCK, session: 'user' },
};
