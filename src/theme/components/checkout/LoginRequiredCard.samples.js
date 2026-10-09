// A static notice: only the filled state exists.
export const notApplicable = ['empty', 'loading', 'error'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { loginHref: '/login', registerHref: '/register' } },
  hint: { label: 'With the return hint', props: { showReturnHint: true } },
};
