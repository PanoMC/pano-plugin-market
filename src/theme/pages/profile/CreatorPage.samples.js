// Sample data of the view, pure data (doc 02 section 7).
import { CLOCK } from '../../components/store/storeSampleData.js';
import { CREATOR } from '../pageSampleData.js';

export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data: CREATOR.filled }, controllers: CLOCK, session: 'user' },
  empty: { props: { data: CREATOR.empty }, controllers: CLOCK, session: 'user' },
  error: { props: { data: CREATOR.error }, controllers: CLOCK, session: 'user' },
};
