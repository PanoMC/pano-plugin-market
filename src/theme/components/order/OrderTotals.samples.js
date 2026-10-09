import { orderTotals, paidOrder } from '../checkout/sampleData.js';

// A list of rows: loading and errors belong to the order page around it.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { order: paidOrder } },
  credits: {
    label: 'Part paid in credits',
    props: {
      order: {
        ...paidOrder,
        credits: { name: 'Coins' },
        totals: { ...orderTotals, creditAmount: 20, creditValue: 5, gatewayAmount: 9.39 },
      },
    },
  },
  empty: { props: { order: { ...paidOrder, totals: null } } },
};
