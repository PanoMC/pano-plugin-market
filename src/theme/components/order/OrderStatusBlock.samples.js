import { awaitingOrder, failedOrder, orderView, paidOrder } from '../checkout/sampleData.js';

const extras = {
  refundPending: false,
  buyerActionUrl: null,
  testMode: false,
  isGift: false,
  recipientUsername: null,
};

// The block always shows one status: there is no empty state.
export const notApplicable = ['empty'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { view: orderView.paid, order: paidOrder, extras } },
  awaiting: {
    label: 'Awaiting payment',
    props: { view: orderView.awaiting, order: awaitingOrder, extras, left: 25 * 60000 },
  },
  loading: { props: { view: orderView.confirming, order: awaitingOrder, extras } },
  error: { props: { view: orderView.failed, order: failedOrder, extras } },
  limited: {
    label: 'Limited view',
    props: {
      view: orderView.limited,
      order: paidOrder,
      extras,
      loginHref: '/login',
    },
  },
  gift: {
    label: 'Gift in test mode',
    props: {
      view: orderView.paid,
      order: paidOrder,
      extras: { ...extras, testMode: true, isGift: true, recipientUsername: 'Alex' },
    },
  },
};
