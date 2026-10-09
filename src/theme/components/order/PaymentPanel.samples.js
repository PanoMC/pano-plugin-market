import { NOW, awaitingOrder, failedOrder, orderView, paidOrder } from '../checkout/sampleData.js';

const withStart = (start, extra = {}) => ({
  ...awaitingOrder,
  payment: { ...awaitingOrder.payment, start },
  ...extra,
});

// The panel appears only while a payment is open: nothing to load, and an unusable start is its error.
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { id: awaitingOrder.publicId, order: awaitingOrder, view: orderView.awaiting },
    controllers: { 'market/clock': { now: NOW } },
  },
  iframe: {
    label: 'Gateway page in a frame',
    props: {
      id: awaitingOrder.publicId,
      order: withStart({
        kind: 'IFRAME',
        iframe: { url: 'https://example.com/', heightPx: 480 },
        expiresAt: null,
      }),
      view: orderView.awaiting,
    },
  },
  empty: {
    label: 'Order already paid: no panel',
    props: { id: paidOrder.publicId, order: paidOrder, view: orderView.paid },
  },
  error: {
    props: {
      id: awaitingOrder.publicId,
      order: withStart(
        { kind: 'IFRAME', iframe: { url: 'http://example.com/' }, expiresAt: null },
        { canRetryPayment: false },
      ),
      view: orderView.awaiting,
    },
  },
  failed: {
    label: 'Failed order: no panel',
    props: { id: failedOrder.publicId, order: failedOrder, view: orderView.failed },
  },
};
