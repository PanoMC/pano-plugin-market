import { awaitingOrder, paidOrder } from '../checkout/sampleData.js';

const instructions = {
  body: '<p>Send the amount to the account below and quote your order number.</p>',
  fields: [
    { label: 'Account holder', value: 'Pano Store Ltd', copyable: true },
    { label: 'IBAN', value: 'DE00 0000 0000 0000 0000 00', copyable: true },
    { label: 'Reference', value: 'Order 1042', copyable: true },
    { label: 'Amount', value: '$14.39' },
  ],
};
const order = {
  ...awaitingOrder,
  payment: { ...awaitingOrder.payment, methodId: 'bank-transfer' },
};

// Static details: loading and errors belong to the order page around it.
export const notApplicable = ['loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { id: order.publicId, order, instructions } },
  empty: {
    props: {
      id: paidOrder.publicId,
      order: paidOrder,
      instructions: { body: '', fields: [] },
      readonly: true,
    },
  },
  readonly: {
    label: 'Transfer reported',
    props: { id: order.publicId, order, instructions, readonly: true },
  },
};
