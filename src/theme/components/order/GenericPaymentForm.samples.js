const continuePayment = async () => ({ ok: true });

const fields = [
  { key: 'notice', type: 'NOTICE', label: 'Enter the details of your account to continue.' },
  {
    key: 'holder',
    type: 'TEXT',
    label: 'Account holder',
    required: true,
    placeholder: 'Alex Miner',
  },
  { key: 'amount', type: 'READONLY', label: 'Amount', help: '$14.39' },
  {
    key: 'bank',
    type: 'SELECT',
    label: 'Bank',
    required: true,
    options: [
      { value: 'a', label: 'First Bank' },
      { value: 'b', label: 'Second Bank' },
    ],
  },
  { key: 'save', type: 'SWITCH', label: 'Remember this account' },
];

// A form: it has no empty or loading state of its own.
export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { fields, continuePayment } },
  error: {
    props: {
      fields,
      continuePayment: async () => ({ ok: false, code: 'PAYMENT_FIELD_INVALID' }),
    },
  },
  waiting: {
    label: 'Waiting before a retry',
    props: { fields, continuePayment, waiting: true, seconds: 30 },
  },
};
