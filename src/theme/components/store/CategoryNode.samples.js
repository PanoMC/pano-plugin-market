// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['empty', 'error', 'loading'];

const row = { id: 10, name: 'Ranks', icon: 'fa-crown', color: null, productsCount: 2, depth: 0 };

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { row } },
  active: { props: { row, active: true }, label: 'Selected category' },
  nested: {
    props: { row: { ...row, id: 11, name: 'Monthly', icon: null, depth: 1, productsCount: 1 } },
    label: 'Child category',
  },
};
