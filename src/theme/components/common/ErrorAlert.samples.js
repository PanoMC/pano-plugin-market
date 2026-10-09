export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { message: 'The payment provider did not answer.', retryLabel: 'Try again' } },
  empty: { props: {} },
};
