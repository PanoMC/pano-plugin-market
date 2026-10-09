const legal = {
  required: true,
  id: 'terms-2',
  title: 'terms of sale',
  content: '<p>The terms of sale.</p>',
};

// A checkbox: it has no loading state, and unchecked is its empty state.
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { legal, checked: true } },
  empty: { props: { legal, checked: false } },
  error: { props: { legal, checked: false, invalid: true } },
  updated: { label: 'Text changed on the page', props: { legal, checked: false, updated: true } },
};
