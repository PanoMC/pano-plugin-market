import { credits } from './sampleData.js';

const base = { credits, config: { mixedCredit: true }, currency: 'USD' };

// The balance is part of the quote: there is nothing to wait for and no empty state.
export const notApplicable = ['empty', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { ...base, useCredits: 25 } },
  all: { label: 'Pay everything in credits', props: { ...base, payWithCredits: true } },
  error: { props: { ...base, alertKey: 'theme.errors.CREDITS_REDUCED', alertMax: 12 } },
};
