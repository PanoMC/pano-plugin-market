import { quote, shippingQuote } from './sampleData.js';

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      quote,
      couponCode: 'WELCOME10',
      couponState: { status: 'APPLIED', code: 'WELCOME10' },
    },
  },
  shipping: { label: 'With shipping', props: { quote: shippingQuote } },
  empty: { props: { quote: null } },
  loading: { props: { quote, quoting: true } },
  error: {
    props: {
      quote: {
        ...quote,
        lines: [{ ...quote.lines[0], errors: ['OUT_OF_STOCK'] }, quote.lines[1]],
      },
      couponCode: 'NOPE',
      couponState: {
        status: 'INVALID',
        reason: 'COUPON_NOT_FOUND',
        messageKey: 'theme.errors.COUPON_NOT_FOUND',
      },
    },
  },
};
