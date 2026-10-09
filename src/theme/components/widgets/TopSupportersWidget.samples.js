const NOW = 1760000000000;
const DAY = 24 * 3600 * 1000;

const data = {
  goals: [
    {
      id: 1,
      name: 'Server upgrade',
      description: 'A faster machine for everyone.',
      metric: 'REVENUE',
      progress: 180,
      target: 300,
      currency: 'USD',
      percent: 60,
      endsAt: NOW + 2 * DAY,
    },
    {
      id: 2,
      name: 'Orders this month',
      description: '',
      metric: 'ORDERS',
      progress: 100,
      target: 100,
      currency: '',
      percent: 100,
      endsAt: 0,
    },
  ],
  topSupporters: [
    { username: 'Steve', rank: 1, total: 240 },
    { username: 'Alex', rank: 2, total: 180 },
    { username: 'Herobrine', rank: 3, total: 95 },
  ],
  recentBuyers: [
    {
      username: 'Steve',
      productNames: ['VIP rank', 'Crate key'],
      amount: 24.99,
      currency: 'USD',
      createdAt: NOW - 3600 * 1000,
    },
    {
      username: 'Alex',
      productNames: ['Fly kit'],
      amount: 9.99,
      currency: 'USD',
      createdAt: NOW - 5 * 3600 * 1000,
    },
  ],
  stats: { ordersToday: 3, ordersTotal: 412, customersTotal: 120, productsTotal: 18 },
  sidebars: ['home', 'profile'],
};

export const notApplicable = ['error', 'loading'];

/** @type {import('@panomc/plugin-kit').Samples} */
export default {
  filled: { props: { data, currency: 'USD' } },
  empty: { props: { data: {} }, label: 'No supporters: nothing renders' },
};
