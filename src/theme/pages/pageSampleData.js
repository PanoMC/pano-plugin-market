// Sample data of the page views (doc 02 section 7): the `data` prop each page gets from its load, built with the same pure
// result mappers the loads use, so a sample can never drift from the shape the page reads. No clock access.
import {
  CATEGORIES,
  COMPARISON,
  NOW,
  PRODUCT,
  PRODUCTS,
  SALE_PRODUCT,
  SETTINGS,
  SUBSCRIPTION_PRODUCT,
} from '../components/store/storeSampleData.js';
import { PRODUCT_DETAIL } from '../components/product/productSampleData.js';
import { resolveProductLoad } from '../components/product/productModel.js';
import { resolveCheckoutLoad } from '../lib/checkoutModel.js';
import { resolveOrderLoad } from '../lib/orderState.js';
import { resolveCreditsLoad, resolvePurchasesLoad } from '../lib/profileModel.js';
import { DEFAULT_FILTER } from '../lib/storeFilter.js';
import { resolveStoreLoad } from '../lib/storeLoad.js';
import { resolveCreatorLoad, resolveSubscriptionsLoad } from '../lib/subscriptionModel.js';

const DAY = 24 * 3600 * 1000;
const list = (items, key = 'items') => ({
  ok: true,
  [key]: items,
  items,
  page: { number: 1, size: 20, totalItems: items.length, totalPages: 1 },
});

const storeResponse = (products) => ({
  ...list(products, 'products'),
  settings: SETTINGS,
  categories: CATEGORIES,
  featured: products.filter((p) => p.featured),
  bestsellers: products.slice(0, 2),
  comparisons: products.length ? [COMPARISON] : [],
  comparisonProducts: products,
});

const storeData = (products) =>
  resolveStoreLoad({
    store: storeResponse(products),
    list: null,
    widgets: { ok: true },
    filter: DEFAULT_FILTER,
    origin: 'https://example.com',
  }).data;

export const STORE = { filled: storeData(PRODUCTS), empty: storeData([]) };
export const STORE_ERROR = { state: 'ERROR', code: 'NETWORK' };
export const STORE_DISABLED = { state: 'DISABLED' };

const productData = (res) =>
  resolveProductLoad({
    res,
    settings: SETTINGS,
    slug: PRODUCT.slug,
    origin: 'https://example.com',
  }).data;

export const PRODUCT_PAGE = {
  filled: productData({ ok: true, product: PRODUCT_DETAIL }),
  sale: productData({
    ok: true,
    product: {
      ...PRODUCT_DETAIL,
      ...SALE_PRODUCT,
      hasVariants: false,
      variants: [],
      variantOptions: null,
    },
  }),
  subscription: productData({
    ok: true,
    product: {
      ...PRODUCT_DETAIL,
      ...SUBSCRIPTION_PRODUCT,
      hasVariants: false,
      variants: [],
      variantOptions: null,
    },
  }),
  error: productData({ ok: false, code: 'NETWORK' }),
  disabled: productData({ ok: false, code: 'STORE_DISABLED' }),
};

export const ORDER = {
  publicId: 'ord_8H2KQ',
  number: 1042,
  status: 'COMPLETED',
  createdAt: NOW - DAY,
  currency: 'USD',
  email: 'steve@example.com',
  limited: false,
  items: [
    {
      id: 1,
      name: PRODUCT.name,
      variantName: null,
      quantity: 1,
      lineTotal: 9.99,
      imageFileName: null,
      targetServerName: 'Survival',
      expiresAt: null,
      delivery: { state: 'DELIVERED' },
      fieldValues: {},
    },
  ],
  totals: { subtotal: 9.99, discount: 0, tax: 0, shipping: 0, total: 9.99, credits: 0 },
  payment: null,
  shipments: [],
  invoiceAvailable: false,
};

const orderData = (res) =>
  resolveOrderLoad({ id: ORDER.publicId, res, settings: SETTINGS, returnHint: null }).data;

export const ORDER_PAGE = {
  filled: orderData({ ok: true, order: ORDER }),
  error: orderData({ ok: false, code: 'NETWORK' }),
};

export const CHECKOUT_CONFIG = {
  guestCheckout: true,
  billingInfoMode: 'NONE',
  addressFields: [],
  legal: { required: false },
  credits: { enabled: false },
};

const checkoutData = (res) => resolveCheckoutLoad({ res, settings: SETTINGS, topup: null }).data;

export const CHECKOUT = {
  filled: checkoutData({ ok: true, config: CHECKOUT_CONFIG }),
  error: checkoutData({ ok: false, code: 'NETWORK' }),
};

export const SUMMARY = {
  ok: true,
  creditsEnabled: true,
  creditBalance: 1234.5,
  creditName: 'Credits',
  activeSubscriptionCount: 1,
  subscriptionCount: 2,
  isCreator: true,
};

export const PURCHASES = {
  filled: resolvePurchasesLoad({
    orders: list([
      {
        publicId: ORDER.publicId,
        number: ORDER.number,
        createdAt: ORDER.createdAt,
        itemNames: [PRODUCT.name, SALE_PRODUCT.name],
        total: 17.49,
        currency: 'USD',
        status: 'COMPLETED',
      },
    ]),
    entitlements: {
      ok: true,
      items: [
        { id: 1, productName: PRODUCT.name, variantName: null, expiresAt: NOW + 20 * DAY },
      ],
    },
    summary: SUMMARY,
    filter: { page: 1, status: '' },
    settings: SETTINGS,
  }).data,
  empty: resolvePurchasesLoad({
    orders: list([]),
    entitlements: { ok: true, items: [] },
    summary: SUMMARY,
    filter: { page: 1, status: '' },
    settings: SETTINGS,
  }).data,
};

export const CREDITS = {
  filled: resolveCreditsLoad({
    credits: {
      ...list(
        [
          {
            id: 1,
            type: 'TOP_UP',
            amount: 500,
            balanceAfter: 1234.5,
            createdAt: NOW - 2 * DAY,
            note: null,
          },
        ],
        'entries',
      ),
      balance: 1234.5,
      creditName: 'Credits',
    },
    summary: SUMMARY,
    settings: SETTINGS,
  }).data,
  empty: resolveCreditsLoad({
    credits: { ...list([], 'entries'), balance: 0, creditName: 'Credits' },
    summary: SUMMARY,
    settings: SETTINGS,
  }).data,
  error: resolveCreditsLoad({
    credits: { ok: false, code: 'NETWORK' },
    summary: SUMMARY,
    settings: SETTINGS,
  }).data,
};

export const SUBSCRIPTIONS = {
  filled: resolveSubscriptionsLoad({
    subscriptions: {
      ok: true,
      items: [
        {
          id: 'sub_1',
          productName: SUBSCRIPTION_PRODUCT.name,
          status: 'ACTIVE',
          price: 5,
          currency: 'USD',
          intervalUnit: 'MONTH',
          intervalCount: 1,
          currentPeriodEnd: NOW + 12 * DAY,
          cancelAtPeriodEnd: false,
          canCancel: true,
          canResume: true,
          canManageAtGateway: false,
        },
      ],
    },
    summary: SUMMARY,
  }).data,
  empty: resolveSubscriptionsLoad({
    subscriptions: { ok: true, items: [] },
    summary: SUMMARY,
  }).data,
  error: resolveSubscriptionsLoad({
    subscriptions: { ok: false, code: 'NETWORK' },
    summary: SUMMARY,
  }).data,
};

export const CREATOR = {
  filled: resolveCreatorLoad({
    creator: {
      ...list(
        [{ orderNumber: 1042, amount: 1.25, state: 'AVAILABLE', createdAt: NOW - DAY }],
        'earnings',
      ),
      totals: { earned: 12.5, paidOut: 5, available: 7.5, currency: 'USD' },
      codes: [
        {
          code: 'STEVE10',
          unit: 'PERCENT',
          discount: 10,
          commissionPercent: 5,
          usedCount: 12,
          status: 'ACTIVE',
        },
      ],
      payouts: [{ amount: 5, method: 'CREDIT', state: 'PAID', paidAt: NOW - 7 * DAY }],
    },
    summary: SUMMARY,
  }).data,
  empty: resolveCreatorLoad({
    creator: { ...list([], 'earnings'), totals: {}, codes: [], payouts: [] },
    summary: SUMMARY,
  }).data,
  error: resolveCreatorLoad({ creator: { ok: false, code: 'NETWORK' }, summary: SUMMARY }).data,
};
