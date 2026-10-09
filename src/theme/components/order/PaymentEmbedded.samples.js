import { awaitingOrder } from '../checkout/sampleData.js';

const continuePayment = async () => ({ ok: true });
const fields = [
  { key: 'holder', type: 'TEXT', label: 'Account holder', required: true },
  { key: 'iban', type: 'TEXT', label: 'IBAN', required: true },
];
const start = (embedded) => ({ kind: 'EMBEDDED', url: null, expiresAt: null, embedded });
const payment = { ...awaitingOrder.payment };

// The step starts as "loading" for a moment and then settles; its other outcomes are listed below.
export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    label: 'Generic form (no gateway view installed)',
    props: {
      order: awaitingOrder,
      payment,
      start: start({ component: 'market:checkout:payment:sample', fields }),
      continuePayment,
    },
  },
  error: {
    label: 'A script that is not https',
    props: {
      order: awaitingOrder,
      payment,
      start: start({
        component: 'market:checkout:payment:sample',
        scripts: ['http://example.com/pay.js'],
      }),
      continuePayment,
    },
  },
  missing: {
    label: 'Nothing to show',
    props: { order: awaitingOrder, payment, start: start({}), continuePayment },
  },
};
