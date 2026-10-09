import { cartWithQuote } from '../checkout/sampleData.js';

const request = {
  line: { productId: 9, variantId: 0, quantity: 1 },
  product: { id: 9, name: 'Gift Card' },
};

// The modal asks one question; it has no loading or error state.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {},
    controllers: { 'market/cart': { ...cartWithQuote, replaceRequest: request } },
  },
  empty: { props: {}, controllers: { 'market/cart': cartWithQuote } },
};
