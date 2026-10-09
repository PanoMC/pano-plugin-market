// Sample data of the view, pure data (doc 02 section 7).
import { SETTINGS } from './storeSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { settings: { ...SETTINGS, testMode: true } } },
  empty: { props: { settings: SETTINGS }, label: 'Live mode: nothing is drawn' },
};
