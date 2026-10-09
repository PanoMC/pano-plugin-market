// Sample data of the store and product views (doc 02 section 7): pure data, no clock access. `NOW` is the instant the
// catalogue clock stands at (`market/clock`), the sale of `SALE_PRODUCT` ends five hours after it.
export const NOW = Date.UTC(2026, 9, 8, 12, 0, 0);

export const SETTINGS = {
  currency: 'USD',
  displayCurrency: 'USD',
  currencies: ['USD', 'EUR'],
  removeCents: false,
  pricesIncludeVat: true,
  creditsEnabled: true,
  creditName: 'Credits',
  testMode: false,
  allowGiftPurchase: true,
  showFeaturedProducts: true,
  showBestsellers: true,
  showComparisons: true,
  modules: { saleBadges: true, saleCountdown: true },
};

export const CLOCK = { 'market/clock': { now: NOW } };

const product = (id, slug, name, price, extra = {}) => ({
  id,
  slug,
  name,
  shortDescription: `${name}, delivered within a minute.`,
  icon: 'fa-gem',
  imageFileName: null,
  price,
  compareAtPrice: null,
  creditPrice: null,
  priceFrom: false,
  inStock: true,
  stock: null,
  featured: false,
  billingMode: 'ONE_TIME',
  period: null,
  hasVariants: false,
  needsOptions: false,
  owned: false,
  sale: null,
  currency: 'USD',
  ...extra,
});

export const PRODUCT = product(1, 'vip-rank', 'VIP Rank', 9.99, {
  featured: true,
  creditPrice: 999,
});

export const SALE_PRODUCT = product(2, 'crate-keys', 'Crate Keys x10', 7.5, {
  compareAtPrice: 10,
  sale: { percent: 25, endsAt: NOW + 5 * 3600 * 1000 },
  stock: 4,
});

export const SOLD_OUT_PRODUCT = product(3, 'founder-cape', 'Founder Cape', 19.99, {
  inStock: false,
  owned: false,
});

export const SUBSCRIPTION_PRODUCT = product(4, 'mvp-monthly', 'MVP Monthly', 5, {
  billingMode: 'SUBSCRIPTION',
  period: { unit: 'MONTH', count: 1 },
  needsOptions: true,
});

export const PRODUCTS = [PRODUCT, SALE_PRODUCT, SUBSCRIPTION_PRODUCT, SOLD_OUT_PRODUCT];

export const CATEGORIES = [
  {
    id: 10,
    name: 'Ranks',
    icon: 'fa-crown',
    color: null,
    productsCount: 2,
    children: [
      { id: 11, name: 'Monthly', icon: null, color: null, productsCount: 1, children: [] },
    ],
  },
  { id: 20, name: 'Keys', icon: 'fa-key', color: null, productsCount: 1, children: [] },
];

export const COMPARISON = {
  id: 1,
  name: 'Ranks compared',
  productIds: [1, 4],
  features: [
    { id: 1, name: 'Colored chat' },
    { id: 2, name: 'Fly in the lobby' },
    { id: 3, name: 'Home slots' },
  ],
  cellValues: { '1-1': 'yes', '1-4': 'yes', '2-1': 'no', '2-4': 'yes', '3-1': '3', '3-4': '10' },
};

export const PRODUCT_MAP = { 1: PRODUCT, 4: SUBSCRIPTION_PRODUCT };
