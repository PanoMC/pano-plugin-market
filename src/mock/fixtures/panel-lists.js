// Preview fixtures of the panel list pages: GET /context and the lists (orders, deliveries,
// shipments, subscriptions, payment events, products, categories) with the filters of the real
// endpoints. Shapes follow the Kotlin routes; money is a decimal number, dates are epoch ms.
import {
  CURRENCIES,
  DAY,
  NOW,
  PLAYERS,
  countFor,
  csvSet,
  listBody,
  matches,
  rows,
} from '../kit.js';
import {
  PAYMENT_METHODS,
  SERVERS,
  categoryRows,
  money,
  orderRows,
  productRows,
  weighted,
} from './world.js';

/** Paging.DEFAULT_PAGE_SIZE of the backend. */
export const PAGE_SIZE = 10;
const HOUR = 3600000;
const BASE = '/api/panel/market';
const cache = new Map();
const memo = (name, volume, build) => {
  const key = `${name}:${volume}`;
  if (!cache.has(key)) cache.set(key, build());
  return cache.get(key);
};
const present = (value) => value !== undefined && value !== null && String(value).trim() !== '';
const inSet = (set, value) => !set || set.has(value);

// ------------------------------------------------------------------------------------- context

const SYMBOLS = { TRY: '\u20BA', USD: '$', EUR: '€', GBP: '£' };

/** GET /context (marketContextBody, with `productMetaSchemas` as a CAT holder gets it). */
export function contextBody() {
  return {
    result: 'ok',
    currency: 'USD',
    currencySymbol: SYMBOLS.USD,
    statsCurrency: 'USD',
    statsCurrencySymbol: SYMBOLS.USD,
    currencyMode: 'MULTI',
    additionalCurrencies: CURRENCIES.filter((c) => c !== 'USD'),
    currencies: ['TRY', 'USD', 'EUR', 'GBP'].map((code) => ({
      code,
      symbol: SYMBOLS[code],
      exponent: 2,
    })),
    creditsEnabled: true,
    creditName: 'Gems',
    creditValue: 0.01,
    allowMixedCreditPayment: true,
    vatPercent: 20,
    showVatInPrice: true,
    testMode: false,
    storeTimeZone: 'Europe/Istanbul',
    revokeOnRefund: true,
    revokeOnChargeback: true,
    billingInfoMode: 'OPTIONAL',
    invoiceEnabled: true,
    mailEnabled: true,
    shippingEnabled: true,
    storeUrl: 'https://play.example.com/store',
    runtimeState: 'READY',
    productMetaSchemas: [],
  };
}

// -------------------------------------------------------------------------------------- orders

function ordersBody({ query, volume }) {
  const statuses = csvSet(query.status);
  const fulfillment = csvSet(query.fulfillmentStatus);
  const shipping = csvSet(query.shippingStatus);
  const sources = csvSet(query.source);
  const from = present(query.from) ? Number(query.from) : null;
  const to = present(query.to) ? Number(query.to) : null;
  const testMode =
    query.testMode === 'true' || query.testMode === '1'
      ? true
      : query.testMode === 'false' || query.testMode === '0'
        ? false
        : null;
  const list = orderRows(volume).filter(
    (o) =>
      inSet(statuses, o.status) &&
      (!present(query.paymentMethodId) || o.paymentMethodId === query.paymentMethodId) &&
      inSet(fulfillment, o.fulfillmentStatus) &&
      inSet(shipping, o.shippingStatus) &&
      inSet(sources, o.source) &&
      (from === null || o.createdAt >= from) &&
      (to === null || o.createdAt <= to) &&
      (testMode === null || o.testMode === testMode) &&
      matches(
        query.search,
        o.playerUsername,
        o.recipientUsername,
        o.paymentLabel,
        o.id,
        o.publicId,
        o.email,
        ...o.items.map((i) => i.productName),
      ),
  );
  return listBody('orders', 'orderCount', list, query, PAGE_SIZE);
}

// ---------------------------------------------------------------------------------- deliveries

const WAITING = ['PENDING', 'SCHEDULED', 'WAITING_SERVER', 'SENT', 'QUEUED', 'SENDING'];
const DELIVERY_ERRORS = [
  ['SERVER_OFFLINE', 'The server did not answer within 30 seconds.'],
  [
    'COMMAND_FAILED',
    'Unknown command. Type "/help" for help. (lp user Steve_the_Builder parent add vip_plus_30_days_survival)',
  ],
  ['PLAYER_NOT_FOUND', 'The player has never joined this server.'],
];

function deliveryStatusOf(fulfillment, r, line) {
  if (fulfillment === 'FULFILLED' || fulfillment === 'REVOKED') return 'CONFIRMED';
  if (fulfillment === 'FAILED') return line === 0 ? 'FAILED' : r < 0.5 ? 'FAILED' : 'CANCELLED';
  if (fulfillment === 'PARTIAL')
    return line === 0 ? 'CONFIRMED' : r < 0.5 ? 'FAILED' : 'WAITING_SERVER';
  return WAITING[Math.floor(r * WAITING.length)];
}

/** Rows of GET /deliveries (DeliveryAdminService.list), newest id first; derived from the orders. */
export function deliveryRows(volume) {
  return memo('deliveries', volume, () => {
    const out = [];
    const orders = orderRows(volume);
    // oldest order first, so ids grow with time
    for (let at = orders.length - 1; at >= 0; at--) {
      const order = orders[at];
      if (order.fulfillmentStatus === 'NONE') continue;
      const phases = order.fulfillmentStatus === 'REVOKED' ? ['GRANT', 'REVOKE'] : ['GRANT'];
      for (const phase of phases) {
        order.items.forEach((item, line) => {
          const [made] = rows(`delivery:${order.id}:${item.id}:${phase}`, 1, (_, random) => {
            const actionType = weighted(random(), [
              ['COMMAND', 7],
              ['PERMISSION', 2],
              ['CREDIT', 1],
              ['WEBHOOK', 1],
            ]);
            const inline = actionType === 'CREDIT' || actionType === 'WEBHOOK';
            const server = inline ? null : SERVERS[Math.floor(random() * SERVERS.length)];
            const status = deliveryStatusOf(order.fulfillmentStatus, random(), line);
            const failed = status === 'FAILED';
            const error = failed
              ? DELIVERY_ERRORS[Math.floor(random() * DELIVERY_ERRORS.length)]
              : null;
            const base = order.paidAt ?? order.createdAt;
            const sentAt = ['SENT', 'CONFIRMED', 'FAILED'].includes(status)
              ? base + 4000 + Math.floor(random() * 60000)
              : null;
            const player = order.recipientUsername ?? order.playerUsername;
            const slug = item.productName
              .toLowerCase()
              .replace(/[^a-z0-9]+/g, '_')
              .slice(0, 24);
            const payload =
              actionType === 'COMMAND'
                ? {
                    command:
                      phase === 'REVOKE'
                        ? `lp user ${player} parent remove ${slug}`
                        : `lp user ${player} parent add ${slug}`,
                  }
                : actionType === 'PERMISSION'
                  ? { permission: `store.${slug}`, value: phase !== 'REVOKE' }
                  : actionType === 'CREDIT'
                    ? { amount: 500 * item.quantity }
                    : {
                        webhook: {
                          url: 'https://discord.example.com/api/webhooks/1234567890/store-feed',
                          event: 'order.delivered',
                        },
                      };
            return {
              id: 0,
              orderId: order.id,
              orderItemId: item.id,
              productName: item.productName,
              playerUsername: player,
              phase,
              actionId: `a${line + 1}`,
              actionType,
              transport: inline ? 'INLINE' : 'MARKET_MC',
              idempotencyKey: `${order.id}-${item.id}-a${line + 1}-${server?.id ?? 0}-0-${phase}`,
              serverId: server?.id ?? null,
              serverName: server?.name ?? null,
              status,
              attempts: failed
                ? 3 + Math.floor(random() * 5)
                : status === 'PENDING' || status === 'SCHEDULED'
                  ? 0
                  : 1,
              requiresOnline: !inline && random() < 0.4,
              waitUntil: status === 'WAITING_SERVER' ? base + 7 * DAY : null,
              cancelRequested: status === 'CANCELLED',
              lastErrorCode: error?.[0] ?? null,
              lastError: error?.[1] ?? null,
              runAfter: status === 'SCHEDULED' ? NOW + 2 * HOUR : base,
              sentAt,
              confirmedAt: status === 'CONFIRMED' ? sentAt + 1500 : null,
              payload,
              result: status === 'CONFIRMED' && actionType === 'COMMAND' ? { output: 'OK' } : null,
            };
          });
          out.push(made);
        });
      }
    }
    out.forEach((row, i) => (row.id = 5000 + i + 1));
    return out.reverse();
  });
}

function deliveriesBody({ query, volume }) {
  // The real endpoint takes one status; the page's "waiting" tab sends a csv, so both are honoured.
  const statuses = csvSet(query.status);
  const term = String(query.search ?? '').trim();
  const list = deliveryRows(volume).filter(
    (d) =>
      inSet(statuses, d.status) &&
      (!present(query.phase) || d.phase === query.phase) &&
      (!present(query.serverId) || String(d.serverId) === String(query.serverId)) &&
      (!present(query.actionType) || d.actionType === query.actionType) &&
      (!term ||
        matches(term, d.playerUsername, d.idempotencyKey) ||
        (/^\d+$/.test(term) && d.orderId === Number(term))),
  );
  return listBody('deliveries', 'deliveryCount', list, query, PAGE_SIZE);
}

/** GET /servers (McServerView.toJson): the option source of the deliveries filter. */
function serversBody({ volume }) {
  const waiting = deliveryRows(volume);
  const count = (id, statuses) =>
    waiting.filter((d) => d.serverId === id && statuses.includes(d.status)).length;
  return {
    result: 'ok',
    servers: SERVERS.map((server, i) => ({
      id: server.id,
      name: server.name,
      type: i === 3 ? 'BUNGEECORD' : 'PAPER',
      connected: i !== 2,
      proxy: i === 3,
      mcComponentVersion: i === 2 ? null : i === 1 ? '1.0.0-alpha.60' : '1.0.0-alpha.65',
      requiredVersion: '1.0.0-alpha.65',
      marketState: i === 2 ? 'OFFLINE' : i === 1 ? 'VERSION_MISMATCH' : 'READY',
      waitingDeliveries: count(server.id, ['WAITING_SERVER', 'PENDING', 'SCHEDULED']),
      queuedDeliveries: count(server.id, ['QUEUED', 'SENT', 'SENDING']),
      downloadUrl: 'https://panomc.com/downloads/pano-mc-plugin',
      platform: i === 3 ? 'BungeeCord' : 'Paper 1.21.4',
      integrations: i === 0 ? ['LuckPerms', 'Vault'] : [],
      settings: null,
    })),
  };
}

// ----------------------------------------------------------------------------------- shipments

const CARRIERS = [
  {
    providerId: 'manual',
    providerName: 'Manual',
    entryMode: 'MANUAL',
    carrierName: 'PTT Kargo',
    service: null,
    url: 'https://gonderitakip.ptt.gov.tr/Track/Verify?q=',
  },
  {
    providerId: 'pano-plugin-market-dhl',
    providerName: 'DHL Express',
    entryMode: 'CARRIER',
    carrierName: 'DHL Express',
    service: 'EXPRESS_WORLDWIDE',
    url: 'https://www.dhl.com/track?tracking-id=',
  },
  {
    providerId: 'pano-plugin-market-ups',
    providerName: 'UPS (United Parcel Service) - Worldwide Expedited',
    entryMode: 'CARRIER',
    carrierName: 'UPS',
    service: '08',
    url: 'https://www.ups.com/track?tracknum=',
  },
];
const TERMINAL_SHIPMENT = new Set(['DELIVERED', 'RETURNED', 'CANCELLED', 'LOST']);

function shipmentStatusesOf(order, r) {
  switch (order.shippingStatus) {
    case 'SHIPPED':
      return [
        weighted(r, [
          ['IN_TRANSIT', 4],
          ['OUT_FOR_DELIVERY', 2],
          ['LABEL_READY', 2],
          ['CREATED', 1],
          ['EXCEPTION', 1],
        ]),
      ];
    case 'DELIVERED':
      return r < 0.2 ? ['CANCELLED', 'DELIVERED'] : ['DELIVERED'];
    case 'PARTIAL':
      return [r < 0.5 ? 'IN_TRANSIT' : 'DELIVERED'];
    case 'RETURNED':
      return [
        weighted(r, [
          ['RETURNED', 3],
          ['RETURNING', 2],
          ['LOST', 1],
        ]),
      ];
    default:
      return [];
  }
}

/** Rows of GET /shipments (ShippingService.shipmentJson, list form), newest first. */
export function shipmentRows(volume) {
  return memo('shipments', volume, () => {
    const out = [];
    for (const order of orderRows(volume)) {
      const [statuses] = rows(`shipment-plan:${order.id}`, 1, (_, random) =>
        shipmentStatusesOf(order, random()),
      );
      statuses.forEach((status, n) => {
        const [made] = rows(`shipment:${order.id}:${n}`, 1, (_, random) => {
          const carrier = CARRIERS[Math.floor(random() * CARRIERS.length)];
          const manual = carrier.entryMode === 'MANUAL';
          const createdAt =
            (order.paidAt ?? order.createdAt) + (2 + n) * HOUR + Math.floor(random() * 20 * HOUR);
          const handed = !['CREATED', 'LABEL_READY', 'CANCELLED'].includes(status);
          const failedCreate = status === 'CREATED' && !manual;
          const tracking =
            status === 'CREATED'
              ? null
              : `${manual ? 'KP' : '1Z'}${String(Math.floor(random() * 1e10)).padStart(10, '0')}TR`;
          const weightGrams = 350 + Math.floor(random() * 900);
          const hasLabel = !manual && status !== 'CREATED';
          const terminal = TERMINAL_SHIPMENT.has(status);
          return {
            id: 0,
            orderId: order.id,
            orderPublicId: order.publicId,
            playerUsername: order.playerUsername,
            methodId: 1 + Math.floor(random() * 3),
            providerId: carrier.providerId,
            providerName: carrier.providerName,
            entryMode: carrier.entryMode,
            serviceCode: carrier.service,
            status,
            merchantReference: `PANO-${order.id}-${n + 1}`,
            carrierReference:
              manual || failedCreate
                ? null
                : `shp_${order.publicId.slice(0, 12).replace(/-/g, '')}`,
            trackingNumber: tracking,
            trackingUrl: tracking ? carrier.url + tracking : null,
            carrierName: carrier.carrierName,
            hasLabel,
            labelFormat: hasLabel ? 'PDF' : null,
            documents: hasLabel ? [{ index: 0, type: 'LABEL', format: 'PDF' }] : [],
            cost: manual ? null : money(6 + random() * 20),
            costCurrency: manual ? null : order.currency,
            weightGrams,
            packages: [
              {
                weightGrams,
                lengthMm: 300,
                widthMm: 250,
                heightMm: 60,
                trackingNumber: tracking,
                hasLabel,
              },
            ],
            testMode: order.testMode,
            stale: status === 'EXCEPTION' || (status === 'IN_TRANSIT' && random() < 0.2),
            lastErrorCode: failedCreate ? 'CARRIER_UNAVAILABLE' : null,
            lastError: failedCreate ? 'The carrier API answered 503 Service Unavailable.' : null,
            estimatedDeliveryAt: handed ? createdAt + 4 * DAY : null,
            shippedAt: handed ? createdAt + 5 * HOUR : null,
            deliveredAt: status === 'DELIVERED' ? createdAt + 3 * DAY : null,
            cancelledAt: status === 'CANCELLED' ? createdAt + HOUR : null,
            lastPolledAt: manual || !handed ? null : createdAt + 2 * DAY,
            nextPollAt: manual || terminal || !handed ? null : NOW + 2 * HOUR,
            itemsReleased: false,
            note: n === 0 && random() < 0.3 ? 'Leave with the neighbour if nobody is home.' : null,
            createdAt,
            updatedAt: createdAt + DAY,
            allowed: {
              retry: failedCreate,
              cancel: !terminal && !(carrier.entryMode === 'CARRIER' && handed),
              track: !manual && status !== 'CANCELLED' && status !== 'CREATED',
              editStatus: !terminal,
              releaseItems: status === 'RETURNED' || status === 'LOST',
              label: true,
            },
          };
        });
        out.push(made);
      });
    }
    out.sort((a, b) => b.createdAt - a.createdAt || b.orderId - a.orderId);
    out.forEach((row, i) => (row.id = 300 + out.length - i));
    return out;
  });
}

function shipmentsBody({ query, volume }) {
  const statuses = csvSet(query.status);
  const stale =
    query.stale === 'true' || query.stale === '1'
      ? true
      : query.stale === 'false' || query.stale === '0'
        ? false
        : null;
  const term = String(query.search ?? '').trim();
  const list = shipmentRows(volume).filter(
    (s) =>
      inSet(statuses, s.status) &&
      (!present(query.providerId) || s.providerId === query.providerId) &&
      (stale === null || s.stale === stale) &&
      (!term ||
        matches(
          term,
          s.trackingNumber,
          s.merchantReference,
          s.carrierReference,
          s.playerUsername,
        ) ||
        s.orderPublicId === term ||
        (/^\d+$/.test(term) && s.orderId === Number(term))),
  );
  return listBody('shipments', 'shipmentCount', list, query, PAGE_SIZE);
}

// ------------------------------------------------------------------------------- subscriptions

const SUBSCRIPTION_PRODUCTS = [
  'VIP+ Rank (30 days)',
  'MVP Rank - Monthly',
  'Fly Pass (monthly, all survival worlds and the creative plot server)',
  'Island Expansion - Weekly Upkeep',
];

/** Rows of GET /subscriptions (SubscriptionViews.panelRow), newest first, unfiltered (PENDING included). */
export function subscriptionRows(volume) {
  return memo('subscriptions', volume, () => {
    const n = countFor(volume, 6, 41);
    return rows('subscription', n, (i, random) => {
      const status = weighted(random(), [
        ['ACTIVE', 12],
        ['PAST_DUE', 4],
        ['PAUSED', 1],
        ['CANCELLED', 4],
        ['EXPIRED', 2],
        ['COMPLETED', 1],
        ['PENDING', 1],
      ]);
      const mode = weighted(random(), [
        ['GATEWAY', 6],
        ['MERCHANT', 3],
        ['MANUAL', 1],
      ]);
      const currency = weighted(
        random(),
        CURRENCIES.map((c, at) => [c, [8, 4, 3, 2][at] ?? 1]),
      );
      const rate = { USD: 1, EUR: 0.9, TRY: 40, GBP: 0.8 }[currency] ?? 1;
      const product = Math.floor(random() * SUBSCRIPTION_PRODUCTS.length);
      const weekly = product === 3;
      const yearly = !weekly && random() < 0.12;
      const period = (weekly ? 7 : yearly ? 365 : 30) * DAY;
      const createdAt = NOW - i * 31 * HOUR - Math.floor(random() * 20 * HOUR);
      const cycleCount = Math.max(
        status === 'PENDING' ? 0 : 1,
        Math.ceil((NOW - createdAt) / period),
      );
      const open = status === 'ACTIVE' || status === 'PAST_DUE' || status === 'PAUSED';
      const currentPeriodEnd = status === 'PENDING' ? null : createdAt + cycleCount * period;
      const player = PLAYERS[Math.floor(random() * PLAYERS.length)];
      const maxCycles = product === 1 && random() < 0.5 ? 12 : null;
      const cancelAtPeriodEnd = status === 'ACTIVE' && random() < 0.15;
      return {
        id: 200 + n - i,
        playerUsername: player,
        userId: 1 + PLAYERS.indexOf(player),
        productName: SUBSCRIPTION_PRODUCTS[product],
        providerId:
          mode === 'MANUAL'
            ? 'bank-transfer'
            : PAYMENT_METHODS[Math.floor(random() * 2)].providerId,
        mode,
        status,
        price: money([14.99, 24.99, 19.99, 2.5][product] * rate * (yearly ? 10 : 1)),
        currency,
        intervalUnit: weekly ? 'WEEK' : yearly ? 'YEAR' : 'MONTH',
        intervalCount: 1,
        cycleCount,
        maxCycles,
        currentPeriodEnd,
        nextChargeAt:
          open && !cancelAtPeriodEnd && status !== 'PAUSED'
            ? status === 'PAST_DUE'
              ? NOW + 6 * HOUR
              : currentPeriodEnd
            : null,
        graceEndsAt: status === 'PAST_DUE' ? NOW + 3 * DAY : null,
        cancelAtPeriodEnd,
        failCount: status === 'PAST_DUE' ? 1 + Math.floor(random() * 3) : 0,
        endReason:
          status === 'CANCELLED'
            ? weighted(random(), [
                ['BUYER_CANCEL', 4],
                ['ADMIN_CANCEL', 2],
                ['CHARGEBACK', 1],
                ['REFUND', 1],
                ['GATEWAY_ENDED', 1],
              ])
            : status === 'EXPIRED'
              ? 'PAYMENT_FAILED'
              : status === 'COMPLETED'
                ? 'COMPLETED'
                : null,
        testMode: random() < 0.08,
        createdAt,
      };
    });
  });
}

function subscriptionsBody({ query, volume }) {
  const statuses = csvSet(query.status);
  const list = subscriptionRows(volume).filter(
    (s) =>
      (statuses ? statuses.has(s.status) : s.status !== 'PENDING') &&
      (!present(query.mode) || s.mode === query.mode) &&
      (!present(query.providerId) || s.providerId === query.providerId) &&
      matches(query.search, s.playerUsername, s.productName),
  );
  return listBody('subscriptions', 'subscriptionCount', list, query, PAGE_SIZE);
}

// ------------------------------------------------------------------------------ payment events

const ATTENTION = new Set(['DEFERRED', 'FAILED', 'REJECTED']);
const EVENT_TYPES = [
  ['payment_intent.succeeded'],
  ['charge.refunded'],
  ['charge.dispute.created'],
  ['PAYMENT.CAPTURE.COMPLETED'],
  ['invoice.payment_failed', 'customer.subscription.updated'],
  [],
];
const EVENT_ERRORS = {
  DEFERRED: [
    'The order is locked by another request, the event will be retried.',
    'Payment attempt not found yet (the event arrived before the checkout finished).',
  ],
  FAILED: [
    'java.lang.IllegalStateException: order 1042 is REFUNDED, cannot apply SUCCEEDED (the provider reported a capture after the refund was completed)',
    'Database connection timed out while applying the event.',
  ],
  REJECTED: ['SIGNATURE_INVALID', 'TIMESTAMP_OUT_OF_TOLERANCE', 'UNKNOWN_INSTALL_TOKEN'],
};

/** Rows of GET /payment-events (PaymentEventAdmin.json with the raw tier), newest first; all inbound. */
export function paymentEventRows(volume) {
  return memo('payment-events', volume, () => {
    const n = countFor(volume, 8, 64);
    const orders = orderRows(volume);
    return rows('payment-event', n, (i, random) => {
      const status = weighted(random(), [
        ['FAILED', 5],
        ['DEFERRED', 4],
        ['REJECTED', 4],
        ['PROCESSED', 3],
        ['DUPLICATE', 1],
        ['RECEIVED', 1],
      ]);
      const provider = PAYMENT_METHODS[Math.floor(random() * 2)];
      const order =
        status === 'REJECTED' || !orders.length
          ? null
          : orders[Math.floor(random() * orders.length)];
      const types = EVENT_TYPES[Math.floor(random() * EVENT_TYPES.length)];
      const errors = EVENT_ERRORS[status];
      const id = 9000 + n - i;
      const key = `evt_${id.toString(36)}${Math.floor(random() * 1e9).toString(36)}`;
      return {
        id,
        providerId: provider.providerId,
        direction: 'IN',
        channel: random() < 0.85 ? 'WEBHOOK' : 'RETURN',
        eventKey: status === 'REJECTED' && random() < 0.5 ? null : key,
        paymentId: order ? order.id * 2 : null,
        orderId: order?.id ?? null,
        verified: status === 'REJECTED' ? false : true,
        status,
        eventTypes: status === 'REJECTED' ? [] : types,
        responseStatus:
          status === 'REJECTED'
            ? 400
            : status === 'FAILED'
              ? 500
              : status === 'DEFERRED'
                ? 503
                : 200,
        error: errors ? errors[Math.floor(random() * errors.length)] : null,
        remoteIp: `54.187.${Math.floor(random() * 255)}.${Math.floor(random() * 255)}`,
        attempts: status === 'DEFERRED' ? 1 + Math.floor(random() * 6) : 1,
        duplicateCount:
          status === 'DUPLICATE' ? 1 + Math.floor(random() * 4) : random() < 0.15 ? 1 : 0,
        createdAt: NOW - i * 5 * HOUR - Math.floor(random() * 4 * HOUR),
        body: JSON.stringify({
          id: key,
          type: types[0] ?? 'unknown',
          data: {
            object: {
              amount: order ? Math.round(order.totalPrice * 100) : 0,
              currency: (order?.currency ?? 'USD').toLowerCase(),
              client_secret: '[REDACTED]',
            },
          },
        }),
        headers: {
          'content-type': ['application/json'],
          'user-agent': ['Stripe/1.0 (+https://stripe.com/docs/webhooks)'],
          'stripe-signature': ['[REDACTED]'],
        },
        url: `/api/market/payments/${provider.providerId}/webhook/[REDACTED]`,
      };
    });
  });
}

function paymentEventsBody({ query, volume }) {
  const statuses = csvSet(query.status) ?? ATTENTION;
  const list = paymentEventRows(volume).filter(
    (e) =>
      statuses.has(e.status) && (!present(query.providerId) || e.providerId === query.providerId),
  );
  return listBody('events', 'eventCount', list, query, PAGE_SIZE);
}

// ------------------------------------------------------------------------- products, categories

function productsBody({ query, volume }) {
  const search = String(query.search ?? '').trim();
  const list = productRows(volume).filter(
    (p) =>
      (!search || matches(search, p.name, p.slug)) &&
      (!present(query.status) || p.status === query.status) &&
      (!present(query.kind) || p.kind === query.kind) &&
      (!present(query.categoryId) || String(p.categoryId) === String(query.categoryId)),
  );
  return listBody('products', 'productCount', list, query, PAGE_SIZE);
}

/** GET /products/simple (ProductJson.simple): every product, by name. */
function simpleProductsBody({ volume }) {
  const products = productRows(volume)
    .map(({ id, name, kind, billingMode, hasVariants, status }) => ({
      id,
      name,
      kind,
      billingMode,
      hasVariants,
      status,
    }))
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : a.id - b.id));
  return { result: 'ok', products };
}

/** GET /categories: the tree (`children`), a category whose parent is filtered out becomes a root. */
function categoriesBody({ query, volume }) {
  const search = String(query.search ?? '').trim();
  const flat = categoryRows(volume).filter((c) => !search || matches(search, c.name));
  const ids = new Set(flat.map((c) => c.id));
  const node = (category) => ({
    ...category,
    children: flat
      .filter((c) => c.parentId === category.id)
      .sort((a, b) => a.position - b.position)
      .map(node),
  });
  const categories = flat
    .filter((c) => c.parentId === null || !ids.has(c.parentId))
    .sort((a, b) => a.position - b.position)
    .map(node);
  return { result: 'ok', categories, categoryCount: flat.length, totalPage: 1 };
}

// -------------------------------------------------------------------------------------- export

export const routes = [
  { method: 'GET', path: `${BASE}/context`, handler: contextBody },
  { method: 'GET', path: `${BASE}/orders`, handler: ordersBody },
  { method: 'GET', path: `${BASE}/deliveries`, handler: deliveriesBody },
  { method: 'GET', path: `${BASE}/servers`, handler: serversBody },
  { method: 'GET', path: `${BASE}/shipments`, handler: shipmentsBody },
  { method: 'GET', path: `${BASE}/subscriptions`, handler: subscriptionsBody },
  { method: 'GET', path: `${BASE}/payment-events`, handler: paymentEventsBody },
  { method: 'GET', path: `${BASE}/products/simple`, handler: simpleProductsBody },
  { method: 'GET', path: `${BASE}/products`, handler: productsBody },
  { method: 'GET', path: `${BASE}/categories`, handler: categoriesBody },
];

export const pages = [
  { side: 'panel', label: 'Orders', href: '/market/orders' },
  { side: 'panel', label: 'Deliveries', href: '/market/deliveries' },
  { side: 'panel', label: 'Shipments', href: '/market/shipments' },
  { side: 'panel', label: 'Subscriptions', href: '/market/subscriptions' },
  { side: 'panel', label: 'Payment events', href: '/market/payment-events' },
  { side: 'panel', label: 'Products', href: '/market/products' },
  { side: 'panel', label: 'Categories', href: '/market/categories' },
];
