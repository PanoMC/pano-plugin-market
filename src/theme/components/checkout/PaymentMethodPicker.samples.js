import { quote } from './sampleData.js';

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { quote, selectedId: 'card', currency: 'USD' } },
  empty: { props: { quote: { ...quote, paymentMethods: [] }, currency: 'USD' } },
  loading: { props: { quote: null } },
  error: {
    props: {
      quote,
      selectedId: 'wallet',
      currency: 'USD',
      alertKey: 'theme.errors.PAYMENT_METHOD_UNAVAILABLE',
    },
  },
};
