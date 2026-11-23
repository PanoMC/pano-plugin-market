// Checkout and order-page fixtures of the development-only preview mode: POST /checkout, the order actions (pay,
// payment/continue, cancel) and one preview order per state of the order page (theme/lib/orderState.js viewState).
// What POST /checkout answers is the drawer's checkout scenario (SCENARIOS of kit.js). The order a checkout "creates" is
// the preview order of the matching state, so the order page shows the same thing afterwards. Deterministic: no clock,
// no randomness; the only memory is `session` (what pay / continue / cancel changed in this browser tab).
import { DAY, failure, publicIdFor } from '../kit.js';
import { orderBody, orderStatusBody, orderView, orders, quoteOf } from './storefront.js';

const API = '/plugins/pano-plugin-market';
const MINUTE = 60000;
const COMPLETED_START = { kind: 'COMPLETED', expiresAt: null };

/** Same origin redirect target: the "gateway" sends the buyer straight back to the order page. */
const origin = () => globalThis.location?.origin || 'http://localhost';

/** `PaymentStart` (04 section 2) of a start kind, or null. `expiresAt` is null: the fixture clock is in the past. */
function startOf(kind, publicId) {
  switch (kind) {
    case 'REDIRECT':
      return {
        kind: 'REDIRECT',
        url: `${origin()}/store/order/${publicId}?return=success`,
        expiresAt: null,
      };
    case 'IFRAME':
      return {
        kind: 'IFRAME',
        iframe: {
          url: 'https://example.com/',
          scripts: [],
          heightPx: 420,
          allow: 'payment',
          resizer: 'NONE',
        },
        expiresAt: null,
      };
    case 'IFRAME_BROKEN':
      return {
        kind: 'IFRAME',
        iframe: {
          url: 'http://example.com/',
          scripts: [],
          heightPx: null,
          allow: null,
          resizer: 'NONE',
        },
        expiresAt: null,
      };
    case 'EMBEDDED_COMPONENT':
    case 'EMBEDDED_FORM':
    case 'EMBEDDED_MISSING':
      return {
        kind: 'EMBEDDED',
        embedded: {
          component: kind === 'EMBEDDED_COMPONENT' ? 'market:checkout:payment:preview' : null,
          props: {},
          scripts: [],
          fields: kind === 'EMBEDDED_MISSING' ? [] : EMBEDDED_FIELDS,
        },
        expiresAt: null,
      };
    case 'INSTRUCTIONS':
    case 'INSTRUCTIONS_READONLY':
      return {
        kind: 'INSTRUCTIONS',
        instructions: {
          body: 'Send the total to the account below and put the reference in the description.<br>The order is delivered once the transfer has been confirmed.',
          fields: [
            { label: 'Account holder', value: 'Preview Minecraft Server Ltd.', copyable: false },
            { label: 'IBAN', value: 'TR00 0000 0000 0000 0000 0000 00', copyable: true },
            { label: 'Reference', value: `PV-${publicId.slice(0, 8)}`, copyable: true },
          ],
          buyerConfirms: kind === 'INSTRUCTIONS',
        },
        expiresAt: null,
      };
    case 'COMPLETED':
      return COMPLETED_START;
    default:
      return null;
  }
}

const EMBEDDED_FIELDS = [
  {
    key: 'phone',
    type: 'TEXT',
    label: 'Mobile phone number',
    placeholder: '+90 5xx xxx xx xx',
    required: true,
  },
  {
    key: 'operator',
    type: 'SELECT',
    label: 'Operator',
    required: true,
    options: [
      { value: 'a', label: 'Operator A' },
      { value: 'b', label: 'Operator B' },
    ],
  },
  { key: 'save', type: 'SWITCH', label: 'Remember this number', required: false },
];

// ------------------------------------------------------------------------------------------------ order states

const PENDING = { status: 'PENDING', paidAt: null, fulfillment: 'NONE', refunded: 0 };
const paid = (o) => ({
  status: 'COMPLETED',
  paidAt: o.createdAt + 4 * MINUTE,
  fulfillment: 'FULFILLED',
});

/** `PROCESSING`: no cancel, no retry, no method list (06 section 10.2). */
const processing = (view) => {
  view.canCancel = false;
  view.canRetryPayment = false;
  delete view.paymentMethods;
  delete view.credits;
};

const SHIPMENT_STATUSES = {
  PENDING: [],
  PARTIAL: ['DELIVERED', 'IN_TRANSIT'],
  SHIPPED: ['OUT_FOR_DELIVERY'],
  RETURNED: ['RETURNED'],
  DELIVERED: ['DELIVERED'],
};

function shipped(shippingStatus) {
  return (view) => {
    const at = view.paidAt ?? view.createdAt;
    const item = view.items[0];

    view.shippingStatus = shippingStatus;
    view.shipping = { methodName: 'Preview Cargo standard', minDays: 2, maxDays: 5 };
    view.shippingAddress = {
      firstName: 'Alex',
      lastName: 'Miner',
      company: null,
      phone: '+1 555 0100',
      country: 'US',
      state: 'WA',
      city: 'Seattle',
      line1: '12 Redstone Avenue',
      line2: null,
      postalCode: '98101',
    };
    view.shipments = SHIPMENT_STATUSES[shippingStatus].map((status, k) => ({
      id: 900 + k,
      status,
      carrierName: 'Preview Cargo',
      trackingNumber: `PV${100200 + k}`,
      trackingUrl: 'https://example.com/track',
      shippedAt: at + DAY,
      deliveredAt: status === 'DELIVERED' ? at + 3 * DAY : null,
      estimatedDeliveryAt: at + 4 * DAY,
      items: [{ orderItemId: item.id, quantity: item.quantity }],
      events: [{ status, description: null, location: 'Seattle, WA', occurredAt: at + 2 * DAY }],
    }));
  };
}

/** The limited view (OrderViews.forRole for a visitor without the token): the allow-list of 11 section 5.2. */
function limitedOf(owner) {
  const out = {};
  for (const key of [
    'publicId',
    'status',
    'fulfillmentStatus',
    'shippingStatus',
    'createdAt',
    'paidAt',
    'currency',
    'testMode',
    'isGift',
  ])
    out[key] = owner[key];
  out.limited = true;
  out.number = null;
  out.totals = { total: owner.totals.total };
  out.recipientUsername = null;
  out.items = owner.items.map((i) => ({
    name: i.name,
    variantName: i.variantName,
    imageFileName: i.imageFileName,
    quantity: i.quantity,
    delivery: i.delivery,
  }));
  out.shipments = [];
  return out;
}

/**
 * One preview order per state of the order page: `{ key, label, raw(o), start?, patch?(view, publicId), hint? }`.
 * `raw` overrides the storefront order the state is built from, `start` is the attempt's start kind, `hint` the
 * `?return=` of the drawer link (the gateway return).
 */
export const STATES = [
  { key: 'awaiting', label: 'Order: waiting for payment, choose a method', raw: () => PENDING },
  {
    key: 'awaiting-redirect',
    label: 'Order: waiting, pay at the gateway (link)',
    raw: () => PENDING,
    start: 'REDIRECT',
  },
  {
    key: 'confirming',
    label: 'Order: back from the gateway, confirming',
    alias: 'awaiting-redirect',
    hint: 'success',
  },
  {
    key: 'cancelled-at-gateway',
    label: 'Order: cancelled at the gateway',
    alias: 'awaiting-redirect',
    hint: 'cancel',
  },
  {
    key: 'awaiting-iframe',
    label: 'Order: waiting, gateway in an iframe',
    raw: () => PENDING,
    start: 'IFRAME',
  },
  {
    key: 'awaiting-iframe-broken',
    label: 'Order: waiting, iframe not usable (UI error)',
    raw: () => PENDING,
    start: 'IFRAME_BROKEN',
  },
  {
    key: 'awaiting-component',
    label: 'Order: waiting, embedded plugin component',
    raw: () => PENDING,
    start: 'EMBEDDED_COMPONENT',
  },
  {
    key: 'awaiting-form',
    label: 'Order: waiting, embedded generic form',
    raw: () => PENDING,
    start: 'EMBEDDED_FORM',
  },
  {
    key: 'awaiting-missing',
    label: 'Order: waiting, embedded UI missing',
    raw: () => PENDING,
    start: 'EMBEDDED_MISSING',
  },
  {
    key: 'awaiting-instructions',
    label: 'Order: waiting, bank transfer instructions',
    raw: () => ({ ...PENDING, bankTransfer: true }),
    start: 'INSTRUCTIONS',
  },
  {
    key: 'attempt-failed',
    label: 'Order: waiting, last attempt failed',
    raw: () => PENDING,
    patch: (view) => {
      view.payment.status = 'FAILED';
      view.payment.failureCode = 'GATEWAY_REJECTED';
    },
  },
  {
    key: 'attempt-failed-buyer',
    label: 'Order: waiting, player name refused (INVALID_BUYER)',
    raw: () => PENDING,
    patch: (view) => {
      view.payment.status = 'FAILED';
      view.payment.failureCode = 'INVALID_BUYER';
    },
  },
  {
    key: 'processing',
    label: 'Order: payment processing',
    raw: () => PENDING,
    patch: (view) => {
      view.payment.status = 'PROCESSING';
      processing(view);
    },
  },
  {
    key: 'processing-bank',
    label: 'Order: bank transfer being checked',
    raw: () => ({ ...PENDING, bankTransfer: true }),
    start: 'INSTRUCTIONS_READONLY',
    patch: (view) => {
      view.payment.status = 'PROCESSING';
      processing(view);
    },
  },
  {
    key: 'review',
    label: 'Order: in review',
    raw: (o) => ({ ...paid(o), status: 'REVIEW', fulfillment: 'PENDING' }),
    patch: (view) => {
      view.payment.status = 'REVIEW';
    },
  },
  { key: 'paid', label: 'Order: paid, delivered', raw: paid },
  {
    key: 'paid-delivering',
    label: 'Order: paid, delivery pending',
    raw: (o) => ({ ...paid(o), fulfillment: 'PENDING' }),
  },
  {
    key: 'paid-partial',
    label: 'Order: paid, partly delivered',
    raw: (o) => ({ ...paid(o), fulfillment: 'PARTIAL' }),
  },
  {
    key: 'paid-delivery-failed',
    label: 'Order: paid, delivery failed',
    raw: (o) => ({ ...paid(o), fulfillment: 'FAILED' }),
  },
  {
    key: 'paid-revoked',
    label: 'Order: paid, delivery revoked',
    raw: (o) => ({ ...paid(o), fulfillment: 'REVOKED' }),
  },
  ...['PENDING', 'PARTIAL', 'SHIPPED', 'RETURNED', 'DELIVERED'].map((s) => ({
    key: `paid-shipping-${s.toLowerCase()}`,
    label: `Order: paid, shipping ${s.toLowerCase()}`,
    raw: paid,
    patch: shipped(s),
  })),
  {
    key: 'paid-extras',
    label: 'Order: paid gift with credits, test mode, refund pending',
    raw: (o) => ({ ...paid(o), isGift: true, recipientUsername: 'EnderQueen' }),
    patch: (view) => {
      view.testMode = true;
      view.refundPending = true;
      view.totals.creditAmount = 500;
      view.totals.creditValue = 5;
      view.totals.gatewayAmount = Math.round((view.totals.total - 5) * 100) / 100;
    },
  },
  {
    key: 'partially-refunded',
    label: 'Order: partially refunded',
    raw: (o) => ({ ...paid(o), status: 'PARTIALLY_REFUNDED', refunded: o.items[0].unit }),
  },
  {
    key: 'refunded',
    label: 'Order: refunded',
    raw: (o) => ({ ...paid(o), status: 'REFUNDED', fulfillment: 'REVOKED', refunded: o.total }),
  },
  {
    key: 'chargeback',
    label: 'Order: chargeback',
    raw: (o) => ({ ...paid(o), status: 'CHARGEBACK', fulfillment: 'REVOKED' }),
  },
  { key: 'failed', label: 'Order: failed', raw: () => ({ ...PENDING, status: 'FAILED' }) },
  {
    key: 'cancelled',
    label: 'Order: cancelled',
    raw: () => ({ ...PENDING, status: 'CANCELLED' }),
  },
  { key: 'expired', label: 'Order: expired', raw: () => ({ ...PENDING, status: 'EXPIRED' }) },
  { key: 'limited', label: 'Order: limited view (no access token)', raw: paid, limited: true },
];

const BY_KEY = new Map(STATES.map((s) => [s.key, s]));

/** Public id of a state's preview order (20 characters, like a real one). */
export const statePublicId = (key) =>
  publicIdFor(
    'store:state',
    STATES.findIndex((s) => s.key === key),
  );

const KEY_BY_ID = new Map(STATES.filter((s) => !s.alias).map((s) => [statePublicId(s.key), s.key]));

/** What pay / continue / cancel changed in this tab: publicId -> state key. */
const session = new Map();

/** Forgets the session changes (tests). */
export function resetSession() {
  session.clear();
  checkoutCount.clear();
}

/** The storefront order a preview order is built from: a list order of that id, else the first order of `few`. */
const templateOf = (publicId) =>
  orders('few').find((o) => o.publicId === publicId) || orders('few')[0];

/** OrderView of state `key` for `publicId`. */
export function stateView(key, publicId) {
  const state = BY_KEY.get(key);
  const base = templateOf(publicId);
  const o = { ...base, ...state.raw(base), publicId };
  const view = orderView(o);

  view.payment.failureCode = null;
  if (o.status === 'PENDING') {
    view.payment.status = state.start ? 'PENDING' : view.payment.status;
    view.expiresAt = null;
  }
  if (state.start) view.payment.start = startOf(state.start, publicId);
  state.patch?.(view, publicId);

  return state.limited ? limitedOf(view) : view;
}

/** The state key of an order id: a change of this tab first, then the preview orders. */
const keyOf = (publicId) => session.get(publicId) ?? KEY_BY_ID.get(publicId) ?? null;

/** The current OrderView of any preview or list order, or null. */
function currentView(publicId, volume) {
  const key = keyOf(publicId);
  if (key) return stateView(key, publicId);
  return orderBody({ params: { publicId }, volume }).order ?? null;
}

// ------------------------------------------------------------------------------------------------ checkout

const SUCCESS_STATE = {
  redirect: 'awaiting-redirect',
  iframe: 'awaiting-iframe',
  'embedded-component': 'awaiting-component',
  'embedded-form': 'awaiting-form',
  instructions: 'awaiting-instructions',
  paid: 'paid',
};

/** The state a successful checkout / pay ends in: the scenario's, or (auto) the start of the chosen method. */
function successState(scenario, body) {
  if (SUCCESS_STATE[scenario]) return SUCCESS_STATE[scenario];
  if (body?.payWithCredits === true) return 'paid';
  if (body?.paymentMethodId === 'bank-transfer') return 'awaiting-instructions';
  return 'awaiting-redirect';
}

const tokenOf = (publicId) => `preview-${publicId}`;

/** The 424 answer: the order exists (PENDING, its attempt FAILED), the buyer may pick another method. */
function providerError(scenario, publicId) {
  const buyer = scenario === 'PAYMENT_PROVIDER_ERROR-INVALID_BUYER';
  const key = buyer ? 'attempt-failed-buyer' : 'attempt-failed';
  const id = publicId ?? statePublicId(key);

  if (publicId) session.set(publicId, key);

  return failure('PAYMENT_PROVIDER_ERROR', {
    details: {
      code: buyer ? 'INVALID_BUYER' : 'GATEWAY_UNREACHABLE',
      order: stateView(key, id),
      orderToken: tokenOf(id),
    },
  });
}

/** How often a once-only failure (PRICE_CHANGED) was answered: every other submit succeeds. */
const checkoutCount = new Map();

function checkoutBody({ body, volume, scenario }) {
  if (scenario === 'NETWORK') throw new Error('preview: the checkout got no answer');
  if (scenario?.startsWith('PAYMENT_PROVIDER_ERROR')) return providerError(scenario, null);

  if (scenario === 'OUT_OF_STOCK') {
    const lines = quoteOf(body, volume)
      .lines.slice(0, 1)
      .map((l) => l.lineKey);
    return failure('OUT_OF_STOCK', { details: { lines } });
  }
  if (scenario === 'BUYER_INFO_REQUIRED')
    return failure('BUYER_INFO_REQUIRED', {
      details: { fields: ['FIRST_NAME', 'LAST_NAME', 'BILLING_ADDRESS'] },
    });
  if (scenario === 'PRICE_CHANGED') {
    // the first submit is told the price changed (with the new quote), the confirming second one goes through
    const n = (checkoutCount.get(scenario) ?? 0) + 1;
    checkoutCount.set(scenario, n);
    if (n % 2 === 1) {
      const quote = quoteOf(body, volume);
      const raise = Math.round(quote.total * 10) / 100;
      const up = (v) => Math.round((v + raise) * 100) / 100;
      return failure('PRICE_CHANGED', {
        details: {
          quote: {
            ...quote,
            subtotal: up(quote.subtotal),
            total: up(quote.total),
            gatewayAmount: up(quote.gatewayAmount),
          },
        },
      });
    }
  }

  const key = successState(scenario, body);
  const publicId = statePublicId(key);
  const state = BY_KEY.get(key);

  session.delete(publicId);

  return {
    order: stateView(key, publicId),
    orderToken: tokenOf(publicId),
    payment: startOf(state.start, publicId) ?? COMPLETED_START,
  };
}

// ------------------------------------------------------------------------------------------------ order actions

function payBody({ params, body, volume, scenario }) {
  const view = currentView(params.publicId, volume);

  if (!view) return failure('NOT_FOUND');
  if (view.status !== 'PENDING' || view.canRetryPayment !== true)
    return failure('ORDER_NOT_PAYABLE');
  if (scenario === 'NETWORK') throw new Error('preview: the payment got no answer');
  if (scenario?.startsWith('PAYMENT_PROVIDER_ERROR'))
    return providerError(scenario, params.publicId);
  if (scenario === 'BUYER_INFO_REQUIRED' && !body?.billingInfo)
    return failure('BUYER_INFO_REQUIRED', {
      details: { fields: ['FIRST_NAME', 'LAST_NAME', 'BILLING_ADDRESS'] },
    });

  const key = successState(scenario, body);

  session.set(params.publicId, key);

  return { payment: startOf(BY_KEY.get(key).start, params.publicId) ?? COMPLETED_START };
}

function continueBody({ params, volume, scenario }) {
  const view = currentView(params.publicId, volume);

  if (!view) return failure('NOT_FOUND');
  if (view.status !== 'PENDING' || view.payment?.start?.kind !== 'EMBEDDED')
    return failure('ORDER_NOT_PAYABLE');
  if (scenario === 'NETWORK') throw new Error('preview: the payment got no answer');
  if (scenario?.startsWith('PAYMENT_PROVIDER_ERROR'))
    return providerError(scenario, params.publicId);

  session.set(params.publicId, 'paid');

  return { payment: COMPLETED_START };
}

function cancelBody({ params, volume }) {
  const view = currentView(params.publicId, volume);

  if (!view) return failure('NOT_FOUND');
  if (view.canCancel !== true) return failure('ORDER_NOT_CANCELLABLE');

  session.set(params.publicId, 'cancelled');

  return {};
}

function getOrder(ctx) {
  const key = keyOf(ctx.params.publicId);
  return key ? { order: stateView(key, ctx.params.publicId) } : orderBody(ctx);
}

function getStatus(ctx) {
  const key = keyOf(ctx.params.publicId);
  if (!key) return orderStatusBody(ctx);

  const view = stateView(key, ctx.params.publicId);

  return {
    status: view.status,
    paymentStatus: view.payment?.status ?? null,
    fulfillmentStatus: view.fulfillmentStatus,
    shippingStatus: view.shippingStatus,
    updatedAt: view.paidAt ?? view.createdAt,
  };
}

/** Listed before the storefront routes in router.js: the order GETs answer the preview orders and fall back to the list. */
export const routes = [
  { method: 'POST', path: `${API}/checkout`, handler: checkoutBody },
  { method: 'POST', path: `${API}/orders/:publicId/pay`, handler: payBody },
  { method: 'POST', path: `${API}/orders/:publicId/payment/continue`, handler: continueBody },
  { method: 'POST', path: `${API}/orders/:publicId/cancel`, handler: cancelBody },
  { method: 'GET', path: `${API}/orders/:publicId/status`, handler: getStatus },
  { method: 'GET', path: `${API}/orders/:publicId`, handler: getOrder },
];

export const pages = STATES.map((s) => ({
  side: 'theme',
  label: s.label,
  href: `/store/order/${statePublicId(s.alias ?? s.key)}${s.hint ? `?return=${s.hint}` : ''}`,
}));
