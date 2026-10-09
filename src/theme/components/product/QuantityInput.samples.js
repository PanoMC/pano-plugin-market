// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['empty', 'error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { value: 2, max: 10 } },
  atMax: { props: { value: 10, max: 10 }, label: 'At the maximum' },
};
