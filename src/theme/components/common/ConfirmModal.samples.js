export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      id: 'sample-confirm',
      title: 'Remove this item?',
      message: 'The item leaves your cart. You can add it again later.',
      confirmLabel: 'Remove',
      cancelLabel: 'Keep it',
    },
  },
  empty: { props: { id: 'sample-confirm-bare', title: 'Are you sure?', variant: 'primary' } },
};
