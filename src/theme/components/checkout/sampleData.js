// Sample data shared by the `*.samples.js` of the cart, checkout and order views (the view catalogue, doc 02 section 7).
// Pure data and pure view helpers only (build rule V3): no package, no Svelte, no controller. Amounts are plain decimals
// like the API sends them. Nothing here ships to a site: only the catalogue loads the compiled samples.
import { viewState } from '../../lib/orderState.js';

export const CURRENCY = 'USD';

/** A fixed "now" (epoch ms) so countdowns and dates look the same on every visit. */
export const NOW = Date.UTC(2026, 9, 8, 12, 0, 0);

export const settings = {
  currency: CURRENCY,
  currencySymbol: '$',
  removeCents: false,
  creditsEnabled: true,
  creditName: 'Coins',
};

export const credits = {
  enabled: true,
  name: 'Coins',
  balance: 120,
  creditTotal: 40,
  payableInCredits: true,
  maxApplicable: 40,
  applied: 0,
  appliedValue: 0,
};

// ---- cart and quote ---------------------------------------------------------------------------------------------------

const quoteLine = (fields) => ({
  kind: 'PRODUCT',
  variantId: 0,
  slug: '',
  variantName: '',
  imageFileName: '',
  maxQuantity: 10,
  listUnitPrice: null,
  creditUnitPrice: null,
  fieldValues: {},
  targetServerId: null,
  errors: [],
  ...fields,
});

export const quoteLines = [
  quoteLine({
    productId: 1,
    slug: 'vip-rank',
    name: 'VIP Rank',
    variantName: '30 days',
    variantId: 11,
    quantity: 1,
    unitPrice: 9.99,
    listUnitPrice: 12.99,
    creditUnitPrice: 30,
    lineTotal: 9.99,
  }),
  quoteLine({
    productId: 2,
    slug: 'crate-key',
    name: 'Crate Key',
    quantity: 3,
    unitPrice: 2,
    lineTotal: 6,
    fieldValues: { Username: 'Steve' },
    targetServerId: 1,
  }),
];

export const paymentMethods = [
  {
    id: 'card',
    label: 'Credit card',
    description: 'Visa, Mastercard',
    icon: 'fa-solid fa-credit-card',
    available: true,
    feeAmount: 0,
  },
  {
    id: 'bank-transfer',
    label: 'Bank transfer',
    description: 'Confirmed by hand within one business day',
    icon: 'fa-solid fa-building-columns',
    available: true,
    feeAmount: 1.5,
  },
  {
    id: 'wallet',
    label: 'Wallet',
    icon: 'fa-solid fa-wallet',
    available: false,
    unavailableReason: 'CURRENCY_NOT_SUPPORTED',
    feeAmount: 0,
  },
];

export const shippingOptions = [
  {
    methodId: 'standard',
    name: 'Standard shipping',
    description: 'Tracked parcel',
    price: 4.5,
    currency: CURRENCY,
    free: false,
    minDays: 3,
    maxDays: 5,
  },
  {
    methodId: 'pickup',
    name: 'Pick up',
    price: 0,
    currency: CURRENCY,
    free: true,
    minDays: 0,
    maxDays: 0,
  },
];

export const quote = {
  currency: CURRENCY,
  lines: quoteLines,
  subtotal: 15.99,
  discountTotal: 3,
  upgradeDiscount: 0,
  couponDiscount: 1.6,
  coupon: { code: 'WELCOME10', valid: true },
  creatorDiscount: 0,
  creatorCode: null,
  requiresShipping: false,
  shippingMethodId: null,
  shippingTotal: 0,
  shippingOptions: [],
  paymentFee: 0,
  vatTotal: 2.4,
  pricesIncludeVat: true,
  total: 14.39,
  gatewayAmount: 14.39,
  paymentMethods,
  credits,
};

/** A quote of physical goods: shipping is chosen from the options. */
export const shippingQuote = {
  ...quote,
  requiresShipping: true,
  shippingMethodId: 'standard',
  shippingTotal: 4.5,
  shippingOptions,
  total: 18.89,
  gatewayAmount: 18.89,
};

export const emptyCart = {
  mode: 'GUEST',
  status: 'IDLE',
  lines: [],
  quote: null,
  quoteStale: false,
  count: 0,
  error: null,
  reduced: [],
  replaceRequest: null,
};

export const cartWithQuote = {
  mode: 'GUEST',
  status: 'IDLE',
  lines: quoteLines.map((line) => ({
    productId: line.productId,
    variantId: line.variantId,
    quantity: line.quantity,
    fieldValues: line.fieldValues,
    targetServerId: line.targetServerId,
    meta: {
      slug: line.slug,
      name: line.name,
      variantName: line.variantName,
      price: line.unitPrice,
    },
  })),
  quote,
  quoteStale: false,
  count: 4,
  error: null,
  reduced: [],
  replaceRequest: null,
};

// ---- addresses --------------------------------------------------------------------------------------------------------

export const address = {
  firstName: 'Alex',
  lastName: 'Miner',
  company: '',
  phone: '+1 555 0100',
  country: 'US',
  state: 'CA',
  city: 'Springfield',
  district: '',
  neighborhood: '',
  line1: '12 Pixel Street',
  line2: 'Apartment 4',
  postalCode: '90210',
};

export const savedAddresses = [
  { id: 1, label: 'Home', isDefault: true, ...address },
  { id: 2, label: '', isDefault: false, ...address, firstName: 'Sam', line1: '7 Redstone Road' },
];

export const billingInfo = {
  type: 'INDIVIDUAL',
  firstName: 'Alex',
  lastName: 'Miner',
  country: 'US',
  line1: '12 Pixel Street',
  city: 'Springfield',
  postalCode: '90210',
};

// ---- orders -----------------------------------------------------------------------------------------------------------

export const orderItems = [
  {
    id: 1,
    name: 'VIP Rank',
    variantName: '30 days',
    quantity: 1,
    lineTotal: 9.99,
    delivery: 'FULFILLED',
    fieldValues: {},
    expiresAt: NOW + 30 * 86400000,
  },
  {
    id: 2,
    name: 'Crate Key',
    quantity: 3,
    lineTotal: 6,
    delivery: 'PENDING',
    fieldValues: { Username: 'Steve' },
    targetServerName: 'Survival',
  },
];

export const orderTotals = {
  subtotal: 15.99,
  discountTotal: 3,
  couponDiscount: 1.6,
  shippingTotal: 0,
  paymentFee: 0,
  vatTotal: 2.4,
  total: 14.39,
  creditAmount: 0,
  creditValue: 0,
  gatewayAmount: 14.39,
  refundedTotal: 0,
};

const baseOrder = {
  number: 1042,
  publicId: 'a1B2c3D4e5F6g7H8i9J0',
  currency: CURRENCY,
  createdAt: NOW - 3600000,
  items: orderItems,
  totals: orderTotals,
  paymentMethods,
  canCancel: false,
  invoiceAvailable: false,
  limited: false,
  testMode: false,
  isGift: false,
  recipientUsername: null,
  fulfillmentStatus: 'FULFILLED',
  shippingStatus: 'NOT_REQUIRED',
  payment: { status: 'COMPLETED', methodId: 'card', label: 'Credit card', start: null },
};

export const paidOrder = {
  ...baseOrder,
  status: 'COMPLETED',
  canCancel: false,
  invoiceAvailable: true,
};

export const awaitingOrder = {
  ...baseOrder,
  status: 'PENDING',
  fulfillmentStatus: 'NONE',
  canCancel: true,
  canRetryPayment: true,
  expiresAt: NOW + 25 * 60000,
  payment: {
    status: 'PENDING',
    methodId: 'card',
    label: 'Credit card',
    start: { kind: 'REDIRECT', url: 'https://pay.example.com/checkout/abc123', expiresAt: null },
  },
};

export const failedOrder = {
  ...baseOrder,
  status: 'FAILED',
  fulfillmentStatus: 'NONE',
  payment: { status: 'FAILED', methodId: 'card', label: 'Credit card', start: null },
};

export const shippedOrder = {
  ...baseOrder,
  status: 'COMPLETED',
  shippingStatus: 'SHIPPED',
  totals: { ...orderTotals, shippingTotal: 4.5, total: 18.89, gatewayAmount: 18.89 },
};

export const orderView = {
  paid: viewState(paidOrder),
  awaiting: viewState(awaitingOrder),
  confirming: viewState(awaitingOrder, 'success', NOW, NOW),
  failed: viewState(failedOrder),
  limited: viewState({ ...paidOrder, limited: true }),
  shipping: viewState(shippedOrder),
};

export const shipments = [
  {
    id: 1,
    carrierName: 'Parcel Express',
    status: 'IN_TRANSIT',
    trackingNumber: 'PX123456789',
    trackingUrl: 'https://track.example.com/PX123456789',
    shippedAt: NOW - 86400000,
    deliveredAt: null,
  },
];
