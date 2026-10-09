// A form of inputs: it has no loading state.
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { user: { username: 'Steve', email: 'steve@example.com' } }, session: 'user' },
  empty: { props: { user: null, guest: { username: '', email: '' } }, session: 'guest' },
  error: {
    props: {
      user: null,
      guest: { username: 'x', email: 'not-an-email' },
      errors: { username: 'USERNAME_INVALID', email: 'EMAIL_INVALID' },
    },
    session: 'guest',
  },
};
