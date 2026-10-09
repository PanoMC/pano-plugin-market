// A dialog with static text: only the filled state exists.
export const notApplicable = ['empty', 'loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      title: 'Terms of sale',
      content: '<h3>Terms</h3><p>All sales are final once the order is delivered.</p>',
    },
  },
};
