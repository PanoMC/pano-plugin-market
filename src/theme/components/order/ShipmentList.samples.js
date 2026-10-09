import { address, billingInfo, shipments } from '../checkout/sampleData.js';

// A list under the order: loading and errors belong to the order page around it.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      shipments,
      shippingAddress: address,
      billingInfo,
      email: 'alex@example.com',
      owner: true,
    },
  },
  empty: { props: { shipments: [], owner: false } },
};
