import { awaitingOrder, orderView, paidOrder } from '../checkout/sampleData.js';

// Two buttons: nothing to load, and the error shows only after a click.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { id: awaitingOrder.publicId, order: awaitingOrder, view: orderView.awaiting } },
  invoice: {
    label: 'Invoice only',
    props: { id: paidOrder.publicId, order: paidOrder, view: orderView.paid },
  },
  empty: {
    label: 'Limited view, no action',
    props: { id: paidOrder.publicId, order: paidOrder, view: orderView.limited },
  },
};
