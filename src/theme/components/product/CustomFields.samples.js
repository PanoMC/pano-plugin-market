// Sample data of the view, pure data (doc 02 section 7).
import { FIELDS } from './productSampleData.js';

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { fields: FIELDS, values: { nickname: 'Steve', color: 'Gold' } } },
  invalid: {
    props: {
      fields: FIELDS,
      values: { nickname: 'S', color: '' },
      errors: { nickname: 'FIELD_TOO_SHORT', color: 'FIELD_REQUIRED' },
    },
    label: 'With errors',
  },
  empty: { props: { fields: [] } },
};
