const settings = { currency: 'USD', currencySymbol: '$', creditsEnabled: true };

const topUp = { freeAmount: true, min: 5, max: 500, creditValue: 1, currency: 'USD' };

const packs = [
  {
    id: 1,
    slug: 'credits-100',
    name: '100 Credits',
    shortDescription: 'A small pack',
    price: 4.99,
    currency: 'USD',
    inStock: true,
    icon: 'fa-coins',
    billingMode: 'ONE_TIME',
  },
  {
    id: 2,
    slug: 'credits-500',
    name: '500 Credits',
    shortDescription: 'The popular pack',
    price: 19.99,
    currency: 'USD',
    inStock: true,
    featured: true,
    icon: 'fa-coins',
    billingMode: 'ONE_TIME',
  },
];

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { topUp, packs, settings, creditName: 'Credits' } },
  empty: {
    props: { topUp: null, packs: [], settings, creditName: 'Credits' },
    label: 'No packs and no free amount',
  },
  packsOnly: {
    props: { topUp: { ...topUp, freeAmount: false }, packs, settings, creditName: 'Credits' },
  },
};
