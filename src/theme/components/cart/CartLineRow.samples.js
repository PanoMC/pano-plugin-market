import { quoteRows } from './cartView.js';
import { quote } from '../checkout/sampleData.js';

const [discounted, withFields] = quoteRows(quote);
const money = (amount) => `$${Number(amount).toFixed(2)}`;

// One row of the cart offcanvas: nothing to load and nothing empty about it.
export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { row: discounted, money, creditsEnabled: true, creditName: 'Coins' } },
  fields: { label: 'With answers and a server', props: { row: withFields, money } },
  error: {
    props: { row: { ...discounted, errors: ['OUT_OF_STOCK', 'QUANTITY_REDUCED'] }, money },
  },
};
