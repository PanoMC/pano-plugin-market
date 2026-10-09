// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { page: 3, totalPages: 8 } },
  empty: { props: { page: 1, totalPages: 1 }, label: 'One page: nothing is drawn' },
};
