const summary = {
  creditsEnabled: true,
  creditBalance: 42.5,
  creditName: 'Credits',
  activeSubscriptionCount: 1,
  subscriptionCount: 2,
  isCreator: true,
};

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data: { summary } } },
  empty: { props: { data: { summary: null } }, label: 'Summary unknown: only the purchases link' },
  creditsOff: {
    props: { data: { summary: { ...summary, creditsEnabled: false, isCreator: false } } },
    label: 'Credits disabled',
  },
};
