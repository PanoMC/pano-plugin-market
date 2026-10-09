import { COUNTRY_CODES } from '../../lib/countries.js';
import { address } from './sampleData.js';

const required = new Set(['firstName', 'lastName', 'country', 'city', 'line1', 'postalCode']);

// A form of inputs: it has no loading state.
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { kind: 'shipping', required, value: address, countries: COUNTRY_CODES } },
  empty: { props: { kind: 'shipping', required, value: {}, countries: COUNTRY_CODES } },
  error: {
    props: {
      kind: 'shipping',
      required,
      value: { ...address, firstName: '', postalCode: '' },
      errors: { firstName: 'REQUIRED', postalCode: 'REQUIRED' },
      countries: COUNTRY_CODES,
    },
  },
};
