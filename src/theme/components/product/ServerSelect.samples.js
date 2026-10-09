// Sample data of the view, pure data (doc 02 section 7).
import { SERVER_CHOICES } from './productSampleData.js';

export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { choices: SERVER_CHOICES, value: 1 } },
  single: { props: { choices: SERVER_CHOICES.slice(0, 1) }, label: 'One server: shown as text' },
  empty: { props: { choices: [] } },
  error: { props: { choices: SERVER_CHOICES, value: null, error: 'SERVER_REQUIRED' } },
};
