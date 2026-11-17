// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      id: 'sample-search',
      initialValue: 'rank',
      placeholder: 'Search',
      ariaLabel: 'Search',
    },
  },
  empty: {
    props: {
      id: 'sample-search-empty',
      initialValue: '',
      placeholder: 'Search',
      ariaLabel: 'Search',
    },
    label: 'Nothing typed yet',
  },
  loading: {
    props: {
      id: 'sample-search-loading',
      initialValue: 'diamond',
      placeholder: 'Search',
      ariaLabel: 'Search',
      searchingLabel: 'Searching',
      searching: true,
    },
    label: 'A search is running',
  },
  large: {
    props: {
      id: 'sample-search-large',
      initialValue: '',
      placeholder: 'Search',
      ariaLabel: 'Search',
      small: false,
    },
    label: 'Normal size input',
  },
};
