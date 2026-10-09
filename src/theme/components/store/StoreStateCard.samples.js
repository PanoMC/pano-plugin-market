// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { icon: 'fa-solid fa-store-slash fa-3x', text: 'The store is closed right now.' },
  },
  empty: { props: { icon: 'fa-solid fa-box-open fa-3x', text: 'There is nothing here yet.' } },
  error: {
    props: {
      icon: 'fa-solid fa-triangle-exclamation fa-3x',
      text: 'The store could not be loaded.',
      onretry: () => {},
    },
  },
};
