// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['error', 'loading'];

const SORTS = [
  { key: 'priority', label: 'Featured' },
  { key: 'price-asc', label: 'Price: low to high' },
  { key: 'price-desc', label: 'Price: high to low' },
  { key: 'newest', label: 'Newest' },
];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { label: 'Sort', options: SORTS, current: 'priority', icon: 'fa-arrow-down-wide-short' },
  },
  other: {
    props: {
      label: 'Sort',
      options: SORTS,
      current: 'price-desc',
      icon: 'fa-arrow-down-wide-short',
    },
    label: 'Another option picked',
  },
  status: {
    props: {
      label: 'Status',
      options: [
        { key: 'all', label: 'All' },
        { key: 'paid', label: 'Paid' },
        { key: 'refunded', label: 'Refunded' },
      ],
      current: null,
    },
    label: 'Nothing picked yet: the first option is shown, default icon',
  },
  empty: { props: { label: 'Sort', options: [], current: null }, label: 'No options' },
};
