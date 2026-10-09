// Sample data of the product views and the product page (doc 02 section 7): pure data. `ProductDetail` of GET /products/:slug.
import { CATEGORIES, PRODUCT, SETTINGS } from '../store/storeSampleData.js';

export const FIELDS = [
  {
    fieldKey: 'nickname',
    label: 'In-game name of the receiver',
    helpText: 'Leave empty to use your own account.',
    type: 'USERNAME',
    required: false,
    options: null,
    pattern: null,
    minLength: 3,
    maxLength: 16,
    minValue: null,
    maxValue: null,
    placeholder: 'Steve',
    defaultValue: null,
  },
  {
    fieldKey: 'color',
    label: 'Prefix color',
    helpText: null,
    type: 'SELECT',
    required: true,
    options: ['Red', 'Aqua', 'Gold'],
    pattern: null,
    minLength: null,
    maxLength: null,
    minValue: null,
    maxValue: null,
    placeholder: null,
    defaultValue: 'Gold',
  },
];

export const VARIANT_OPTIONS = [
  {
    key: 'duration',
    label: 'Duration',
    values: [
      { key: '30', label: '30 days' },
      { key: '90', label: '90 days' },
    ],
  },
];

export const VARIANTS = [
  {
    id: 101,
    name: '30 days',
    optionValues: { duration: '30' },
    price: 9.99,
    compareAtPrice: null,
    creditPrice: null,
    inStock: true,
    stock: null,
    imageFileName: null,
    periodCount: null,
  },
  {
    id: 102,
    name: '90 days',
    optionValues: { duration: '90' },
    price: 24.99,
    compareAtPrice: 29.97,
    creditPrice: null,
    inStock: true,
    stock: null,
    imageFileName: null,
    periodCount: null,
  },
];

export const SERVER_CHOICES = [
  { id: 1, name: 'Survival' },
  { id: 2, name: 'Skyblock' },
];

export const BUNDLE_ITEMS = [
  { productId: 5, variantId: 0, name: 'Crate Keys x10', quantity: 2, imageFileName: null },
  { productId: 6, variantId: 0, name: 'Spawner Pack', quantity: 1, imageFileName: null },
];

export const REQUIRED_PRODUCTS = [
  { id: 7, slug: 'member-rank', name: 'Member Rank', owned: true },
  { id: 8, slug: 'starter-kit', name: 'Starter Kit', owned: false },
];

/** The product page's product: a ranked item with a variant axis, custom fields and two servers. */
export const PRODUCT_DETAIL = {
  ...PRODUCT,
  description:
    '<p><strong>VIP Rank</strong> is one of the most popular items of the store.</p><ul><li>Delivered within a minute</li><li>Works on Survival and Skyblock</li></ul>',
  categoryId: CATEGORIES[0].id,
  categoryName: CATEGORIES[0].name,
  kind: 'RANK',
  hasVariants: true,
  variantOptions: VARIANT_OPTIONS,
  variants: VARIANTS,
  fields: FIELDS,
  serverChoices: SERVER_CHOICES,
  bundleItems: [],
  requiredProducts: REQUIRED_PRODUCTS,
  requireOnlyOne: false,
  limitPerPlayer: null,
  purchasable: { ok: true },
  upgrade: null,
  allowGift: true,
};

export const PRODUCT_SETTINGS = SETTINGS;
