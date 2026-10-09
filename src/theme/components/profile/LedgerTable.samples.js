const NOW = 1760000000000;
const DAY = 24 * 3600 * 1000;

const entries = [
  {
    id: 3,
    type: 'SPEND',
    amount: -12,
    balanceAfter: 30.5,
    createdAt: NOW - DAY,
    note: '',
    orderPublicId: 'ord-8f3k2a',
  },
  {
    id: 2,
    type: 'TOP_UP',
    amount: 25,
    balanceAfter: 42.5,
    createdAt: NOW - 3 * DAY,
    note: 'Top-up',
    orderPublicId: null,
  },
  {
    id: 1,
    type: 'GIFT',
    amount: 17.5,
    balanceAfter: 17.5,
    createdAt: NOW - 9 * DAY,
    note: 'Welcome gift',
    orderPublicId: null,
  },
];

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { entries, creditName: 'Credits' } },
  empty: { props: { entries: [], creditName: 'Credits' } },
};
