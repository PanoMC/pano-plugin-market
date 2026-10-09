// Sample data of the view, pure data (doc 02 section 7).
export const notApplicable = ['empty', 'error', 'loading'];

const buy = (kind, reason = null) => ({ props: { buy: { kind, reason }, slug: 'vip-rank' } });

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: buy('BUY'),
  subscribe: buy('SUBSCRIBE'),
  login: { ...buy('LOGIN', 'LOGIN_REQUIRED'), session: 'guest', label: 'Guest must sign in' },
  soldOut: buy('SOLD_OUT', 'OUT_OF_STOCK'),
  blocked: buy('BLOCKED', 'ALREADY_OWNED'),
  pending: { props: { buy: { kind: 'BUY', reason: null }, slug: 'vip-rank', pending: true } },
};
