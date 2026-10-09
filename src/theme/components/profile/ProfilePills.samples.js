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
  filled: { props: { summary, current: 'credits' } },
  empty: {
    props: { summary: null, current: 'purchases' },
    label: 'Summary unknown: only the purchases link',
  },
};
