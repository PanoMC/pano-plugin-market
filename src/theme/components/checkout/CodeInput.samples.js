const base = { id: 'market-sample-coupon', label: 'Coupon code' };

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: {
    props: { ...base, applied: 'WELCOME10', info: { status: 'APPLIED', code: 'WELCOME10' } },
  },
  empty: { props: { ...base } },
  error: {
    props: {
      ...base,
      applied: 'NOPE',
      info: {
        status: 'INVALID',
        reason: 'COUPON_NOT_FOUND',
        messageKey: 'theme.errors.COUPON_NOT_FOUND',
      },
    },
  },
  loading: { props: { ...base, applied: 'WELCOME10', busy: true } },
};
