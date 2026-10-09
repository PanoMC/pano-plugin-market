import { billingRequirements } from '../../lib/checkoutModel.js';
import { billingInfo } from './sampleData.js';

const requirements = (info, mode = 'REQUIRED') =>
  billingRequirements({ config: { billingInfoMode: mode }, quote: null, info, open: true });

// A form of inputs: it has no loading state.
export const notApplicable = ['loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { req: requirements(billingInfo), info: billingInfo } },
  empty: { props: { req: requirements({}), info: {} } },
  error: {
    props: {
      req: requirements({ type: 'INDIVIDUAL' }),
      info: { type: 'INDIVIDUAL' },
      errors: { firstName: 'REQUIRED', lastName: 'REQUIRED', line1: 'REQUIRED' },
    },
  },
  company: {
    label: 'Company',
    props: {
      req: requirements({
        ...billingInfo,
        type: 'COMPANY',
        company: 'Pixel Works',
        taxNumber: '12345',
      }),
      info: { ...billingInfo, type: 'COMPANY', company: 'Pixel Works', taxNumber: '12345' },
    },
  },
  optional: {
    label: 'Optional and closed',
    props: {
      req: billingRequirements({ config: { billingInfoMode: 'OPTIONAL' }, quote: null, info: {} }),
      info: {},
    },
  },
};
