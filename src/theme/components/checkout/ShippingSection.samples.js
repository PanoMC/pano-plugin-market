import { COUNTRY_CODES } from '../../lib/countries.js';
import { address, savedAddresses, shippingQuote } from './sampleData.js';

const required = new Set(['firstName', 'lastName', 'country', 'city', 'line1', 'postalCode']);
const base = { required, countryCodes: COUNTRY_CODES };

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      ...base,
      loggedIn: true,
      addresses: savedAddresses,
      shippingAddressId: 1,
      address,
      quote: shippingQuote,
      addressReady: true,
      selectedMethodId: 'standard',
    },
    session: 'user',
  },
  empty: { props: { ...base, address: {}, quote: null }, session: 'guest' },
  loading: {
    props: { ...base, address, quote: shippingQuote, quoting: true, addressReady: true },
  },
  error: {
    props: {
      ...base,
      address: { ...address, city: '' },
      errors: { city: 'REQUIRED', method: 'SHIPPING_METHOD_REQUIRED' },
      quote: shippingQuote,
      addressReady: true,
    },
  },
};
