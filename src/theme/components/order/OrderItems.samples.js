import { orderItems } from '../checkout/sampleData.js';

// A list of lines: loading and errors belong to the order page around it.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { items: orderItems, currency: 'USD' } },
  empty: { props: { items: [], currency: 'USD' } },
};
