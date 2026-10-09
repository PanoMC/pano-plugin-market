export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { status: 'COMPLETED' } },
  empty: { props: { status: '' } },
  failed: { props: { status: 'FAILED' }, label: 'A failed order' },
  unknown: { props: { status: 'SOMETHING_NEW' }, label: 'An unknown status is shown as it is' },
};
