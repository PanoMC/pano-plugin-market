import { cartWithQuote, emptyCart } from '../checkout/sampleData.js';

// The cart button of the navbar: it only draws itself in the browser, with items or on a store page.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: {}, controllers: { 'market/cart': cartWithQuote } },
  empty: { props: {}, controllers: { 'market/cart': emptyCart }, session: 'guest' },
};
