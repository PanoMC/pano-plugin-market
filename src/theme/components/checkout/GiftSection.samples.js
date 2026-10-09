// A form of inputs: it has no loading state.
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: {
      isGift: true,
      recipient: 'Alex',
      message: 'Enjoy!',
      lineNames: ['VIP Rank', 'Crate Key'],
    },
  },
  empty: { props: { isGift: false, lineNames: ['VIP Rank'] } },
  error: {
    props: {
      isGift: true,
      recipient: 'nobody',
      lineNames: ['VIP Rank'],
      errors: { recipient: 'RECIPIENT_NOT_FOUND' },
      recipientUnknown: true,
    },
  },
};
