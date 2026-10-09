import { subscriptionView } from '../../lib/subscriptionModel.js';

const row = {
  id: 'sub-1',
  productName: 'VIP rank',
  status: 'ACTIVE',
  price: 9.99,
  currency: 'USD',
  intervalUnit: 'MONTH',
  intervalCount: 1,
  currentPeriodEnd: 1762600000000,
  cancelAtPeriodEnd: false,
  canCancel: true,
  canResume: true,
  canManageAtGateway: false,
  methodLabel: 'Card',
  storedMethodLabel: 'Visa ending 4242',
  renewalOrderPublicId: null,
};

export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { subscription: subscriptionView(row) } },
  empty: {
    props: {
      subscription: subscriptionView({ ...row, canCancel: false, canManageAtGateway: false }),
    },
    label: 'No action available',
  },
  error: {
    props: {
      subscription: subscriptionView({ ...row, status: 'PAST_DUE', canManageAtGateway: true }),
    },
    label: 'Renewal failed',
  },
  cancelling: {
    props: { subscription: subscriptionView({ ...row, cancelAtPeriodEnd: true }) },
    label: 'Ends at the period end',
  },
};
