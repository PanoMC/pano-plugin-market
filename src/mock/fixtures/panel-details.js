// Preview fixtures of the panel detail / secondary pages: order detail, overview (stats, servers,
// health), credits, blocks, discounts / coupons / creator codes, gifts, comparisons and goals.
// Shapes mirror the Kotlin routes; money on the wire is a decimal number (MoneyUtil.toDecimal).
import { DAY, NOW, PLAYERS, countFor, listBody, matches, rng, rows } from '../kit.js';
import { routes as listRoutes } from './panel-lists.js';
import {
  PAYMENT_METHODS,
  SERVERS,
  categoryRows,
  money,
  orderRows,
  productRows,
  weighted,
} from './world.js';

const API = '/api/panel/market';
const HOUR = 3600000;
const MINUTE = 60000;
const PAGE_SIZE = 10; // Paging.DEFAULT_PAGE_SIZE
const NOT_FOUND = { result: 'error', error: 'NOT_FOUND' };
const STORE_CURRENCY = 'USD';
const ADMINS = ['Admin_Kahverengi', 'Mod_Alex'];

const get = (path, handler) => ({ method: 'GET', path: API + path, handler });
const idOf = (raw) => (/^[0-9]{1,15}$/.test(String(raw)) ? Number(raw) : null);
const numberOf = (raw) => (raw === undefined || raw === null || raw === '' ? null : Number(raw));
const ok = (body) => ({ result: 'ok', ...body });

// ================================================================================== order detail

const PAID = new Set(['COMPLETED', 'PARTIALLY_REFUNDED']);
const UNPAID = new Set(['PENDING', 'FAILED', 'CANCELLED', 'EXPIRED']);
const PERMISSIONS = [
  'market.vip',
  'market.vip.plus',
  'market.mvp',
  'essentials.fly',
  'cosmetics.wings',
];
const COUNTRIES = [
  ['US', 'Austin', 'TX', '78701', '500 Congress Avenue'],
  ['DE', 'Berlin', null, '10115', 'Invalidenstrasse 117'],
  ['TR', 'Istanbul', 'Kadikoy', '34710', 'Moda Caddesi No: 12 Daire: 4'],
  ['GB', 'Manchester', null, 'M1 1AE', '1 Piccadilly Gardens'],
];

function addressOf(row, random) {
  const [country, city, state, postalCode, line1] =
    COUNTRIES[Math.floor(random() * COUNTRIES.length)];
  return {
    firstName: row.playerUsername.split('_')[0],
    lastName: 'Blockwright',
    company: null,
    line1,
    line2: null,
    city,
    state,
    postalCode,
    country,
    phone: '+1 555 0100',
  };
}

function deliveryStatusOf(order, random) {
  if (order.status === 'REVIEW') return 'PENDING';
  switch (order.fulfillmentStatus) {
    case 'FULFILLED':
    case 'REVOKED':
      return 'CONFIRMED';
    case 'FAILED':
      return 'FAILED';
    case 'PARTIAL':
      return weighted(random(), [
        ['CONFIRMED', 2],
        ['FAILED', 1],
        ['WAITING_PLAYER', 1],
      ]);
    default:
      return weighted(random(), [
        ['PENDING', 1],
        ['WAITING_SERVER', 2],
        ['WAITING_PLAYER', 2],
        ['QUEUED', 1],
      ]);
  }
}

function buildOrderDetail(row, volume) {
  const random = rng(`order-detail:${row.id}`);
  const products = productRows(volume);
  const paid = !UNPAID.has(row.status);
  const method = PAYMENT_METHODS.find((m) => m.id === row.paymentMethodId) ?? PAYMENT_METHODS[0];
  const physical = row.shippingStatus !== 'NOT_REQUIRED';
  const address = addressOf(row, random);
  const refundRatio = row.totalPrice > 0 ? row.refundedTotal / row.totalPrice : 0;
  const creditRatio = row.totalPrice > 0 ? row.creditValue / row.totalPrice : 0;
  const vatPercent = row.currency === 'USD' ? 0 : 20;
  const actor = ADMINS[row.id % ADMINS.length];
  const paymentId = row.id * 3;
  const subscriptionItem = row.items.find(
    (item) => products[item.productId - 1]?.billingMode === 'SUBSCRIPTION',
  );
  const subscriptionId = paid && subscriptionItem ? row.id + 5000 : null;

  const items = row.items.map((item, at) => {
    const product = products[item.productId - 1];
    const serverId =
      product?.kind === 'CREDIT_PACK' ? null : SERVERS[(row.id + at) % SERVERS.length].id;
    const vatAmount = money((item.lineTotal * vatPercent) / (100 + vatPercent));
    const fullyRefunded = row.status === 'REFUNDED';
    const partRefunded = row.status === 'PARTIALLY_REFUNDED' && at === 0;
    const shipped =
      product?.physical && ['SHIPPED', 'DELIVERED', 'RETURNED'].includes(row.shippingStatus);
    return {
      id: item.id,
      parentItemId: null,
      kind:
        product?.kind === 'CREDIT_PACK'
          ? 'CREDIT_TOPUP'
          : product?.kind === 'BUNDLE'
            ? 'BUNDLE'
            : 'PRODUCT',
      productId: item.productId,
      productName: item.productName,
      variantId: item.variantName ? item.productId * 100 + 1 : null,
      variantName: item.variantName,
      sku: product?.physical ? `SKU-${String(item.productId).padStart(4, '0')}` : null,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      listUnitPrice: item.unitPrice,
      discountAmount: 0,
      upgradeAmount: 0,
      couponAmount: 0,
      vatPercent,
      vatAmount,
      lineTotal: item.lineTotal,
      creditUnitPrice: null,
      creditAmount: money(item.lineTotal * creditRatio * 100),
      fieldValues: at === 0 && row.id % 4 === 0 ? { discord: `${row.playerUsername}#0420` } : null,
      targetServerId: serverId,
      targetServerName: serverId ? SERVERS.find((s) => s.id === serverId).name : null,
      physical: !!product?.physical,
      refundedQuantity: fullyRefunded ? item.quantity : 0,
      refundedAmount: fullyRefunded
        ? item.lineTotal
        : partRefunded
          ? Math.min(item.lineTotal, row.refundedTotal)
          : 0,
      shippedQuantity: shipped ? item.quantity : 0,
      snapshot: {
        slug: product?.slug ?? null,
        imageFileName: null,
        kind: product?.kind ?? 'STANDARD',
        billingMode: product?.billingMode ?? 'ONE_TIME',
        periodUnit: product?.billingMode === 'ONE_TIME' ? null : 'MONTH',
        periodCount: product?.billingMode === 'ONE_TIME' ? null : 1,
        physical: !!product?.physical,
        weightGrams: product?.physical ? 350 : null,
        variantAttributes: item.variantName ? { Color: item.variantName } : null,
        actionCount: product?.physical ? 0 : 2,
      },
      createdAt: item.createdAt,
      updatedAt: item.updatedAt,
      expiresAt:
        paid && product?.billingMode !== 'ONE_TIME'
          ? (row.paidAt ?? row.createdAt) + 30 * DAY
          : null,
    };
  });

  const vatTotal = money(items.reduce((sum, item) => sum + item.vatAmount, 0));
  const refundedGateway = money(row.gatewayAmount * refundRatio);
  const order = {
    id: row.id,
    publicId: row.publicId,
    userId: row.userId,
    playerUsername: row.playerUsername,
    source: row.source,
    buyerKey: `u:${row.userId ?? row.playerUsername.toLowerCase()}`,
    locale: ['en-US', 'tr', 'de'][row.id % 3],
    recipientUsername: row.recipientUsername,
    recipientUserId: row.recipientUsername ? 1 + PLAYERS.indexOf(row.recipientUsername) : null,
    recipientKey: row.recipientUsername ? `p:${row.recipientUsername.toLowerCase()}` : null,
    isGift: row.isGift,
    hideFromBroadcast: row.id % 9 === 0,
    status: row.status,
    statusBeforeDispute: row.status === 'CHARGEBACK' ? 'COMPLETED' : null,
    disputeStatus: row.status === 'CHARGEBACK' ? (row.id % 2 ? 'OPEN' : 'LOST') : 'NONE',
    reviewReason: row.reviewReason,
    reservationState: paid ? 'COMMITTED' : row.status === 'PENDING' ? 'HELD' : 'RELEASED',
    expiresAt: row.status === 'PENDING' ? row.createdAt + DAY : null,
    currency: row.currency,
    baseCurrency: STORE_CURRENCY,
    fxRate: { USD: 1, EUR: 0.9, TRY: 40, GBP: 0.8 }[row.currency] ?? 1,
    displayCurrency: row.currency,
    displayRate: null,
    pricingMode: 'MARKET',
    pricesIncludeVat: true,
    subtotal: row.totalPrice,
    discountTotal: 0,
    couponDiscount: 0,
    creatorDiscount: 0,
    upgradeDiscount: 0,
    shippingTotal: 0,
    shippingVatPercent: 0,
    shippingVatAmount: 0,
    paymentFee: 0,
    paymentFeeVatPercent: 0,
    paymentFeeVatAmount: 0,
    vatTotal,
    totalPrice: row.totalPrice,
    creditAmount: money(row.creditValue * 100),
    creditValue: row.creditValue,
    gatewayAmount: row.gatewayAmount,
    paidAmount: paid ? row.gatewayAmount : 0,
    refundedTotal: row.refundedTotal,
    refundedGatewayAmount: refundedGateway,
    refundedCreditAmount: money((row.refundedTotal - refundedGateway) * 100),
    couponId: row.id % 6 === 0 ? 2 : null,
    creatorCodeId: row.id % 5 === 0 ? 1 : null,
    giftId: row.source === 'GIFT_CODE' ? 1 : null,
    couponCode: row.id % 6 === 0 ? 'WELCOME10' : null,
    creatorCode: row.id % 5 === 0 ? 'DREAMCRAFT' : null,
    paymentId,
    paymentMethodId: row.paymentMethodId,
    paymentLabel: row.paymentLabel,
    paidAt: row.paidAt,
    testMode: row.testMode,
    fulfillmentStatus: row.fulfillmentStatus,
    fulfillmentBy: 'MARKET',
    requiresShipping: physical,
    shippingStatus: row.shippingStatus,
    shippingMethodId: physical ? 1 : null,
    shippingMethodName: physical ? 'Standard shipping (3-5 business days)' : null,
    shippingQuote: physical
      ? {
          methodId: 1,
          name: 'Standard shipping (3-5 business days)',
          price: 0,
          currency: row.currency,
        }
      : null,
    shippingWeightGrams: physical ? 350 : null,
    legalTextId: row.source === 'STOREFRONT' ? 1 : null,
    legalTextVersion: row.source === 'STOREFRONT' ? 3 : null,
    legalAcceptedAt: row.source === 'STOREFRONT' ? row.createdAt : null,
    subscriptionId,
    invoiceId: paid ? row.id + 100 : null,
    note:
      row.id % 4 === 1
        ? 'Player asked on Discord to move the rank to the Skyblock server after delivery.'
        : null,
    createdBy: row.source === 'PANEL' ? 1 : null,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    exchangeRate: paid
      ? money(1 / ({ USD: 1, EUR: 0.9, TRY: 40, GBP: 0.8 }[row.currency] ?? 1))
      : null,
    statsValue: money(
      row.totalPrice / ({ USD: 1, EUR: 0.9, TRY: 40, GBP: 0.8 }[row.currency] ?? 1),
    ),
    statsCurrency: STORE_CURRENCY,
    statsCurrencySymbol: '$',
    email: row.email,
    billingInfo: { ...address, taxId: null },
    shippingAddress: physical ? address : null,
    clientIp: `203.0.113.${row.id % 250}`,
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0',
    giftMessage: row.isGift ? 'Happy birthday! Enjoy the rank :)' : null,
  };

  // ---- payments (a failed first attempt on some orders)
  const paymentStatus = paid
    ? 'SUCCEEDED'
    : { PENDING: 'PENDING', FAILED: 'FAILED', CANCELLED: 'CANCELLED', EXPIRED: 'EXPIRED' }[
        row.status
      ];
  const payments = [];
  if (row.id % 7 === 0) {
    payments.push({
      id: paymentId - 1,
      providerId: method.providerId,
      status: 'FAILED',
      amount: row.gatewayAmount,
      creditAmount: 0,
      paidAmount: 0,
      gatewayTransactionId: null,
      testMode: row.testMode,
      duplicate: false,
      failureMessage: 'Your card was declined.',
      adminMessage: 'card_declined: insufficient_funds',
      createdAt: row.createdAt + 5000,
      paidAt: null,
    });
  }
  payments.push({
    id: paymentId,
    providerId: method.providerId,
    status: row.status === 'REVIEW' ? 'REVIEW' : paymentStatus,
    amount: row.gatewayAmount,
    creditAmount: money(row.creditValue * 100),
    paidAmount: paid ? row.gatewayAmount : 0,
    gatewayTransactionId:
      paid && method.id !== 'credits' ? `txn_${row.publicId.slice(0, 18).replace(/-/g, '')}` : null,
    testMode: row.testMode,
    duplicate: false,
    failureMessage: row.status === 'FAILED' ? 'The payment could not be completed.' : null,
    adminMessage:
      row.status === 'FAILED' ? 'authentication_required: 3-D Secure was abandoned' : null,
    createdAt: row.createdAt + 15000,
    paidAt: row.paidAt,
  });

  // ---- refunds / disputes
  const refunds = [];
  if (row.refundedTotal > 0) {
    refunds.push({
      id: row.id + 300,
      status: 'SUCCEEDED',
      origin: 'PANEL',
      amount: row.refundedTotal,
      gatewayAmount: refundedGateway,
      creditAmount: money((row.refundedTotal - refundedGateway) * 100),
      creditValue: money(row.refundedTotal - refundedGateway),
      currency: row.currency,
      reason:
        row.status === 'REFUNDED'
          ? 'Bought the wrong rank by mistake'
          : 'One crate key was not delivered',
      revoke: row.status === 'REFUNDED',
      revokeFirst: false,
      buyerActionUrl: null,
      failureMessage: null,
      initiatedByUsername: actor,
      createdAt: row.paidAt + 2 * DAY,
      completedAt: row.paidAt + 2 * DAY + 4000,
    });
  }
  const disputes =
    row.status === 'CHARGEBACK'
      ? [
          {
            id: row.id + 400,
            status: order.disputeStatus,
            origin: 'GATEWAY',
            amount: row.gatewayAmount,
            currency: row.currency,
            reason: 'fraudulent',
            openedAt: row.paidAt + 6 * DAY,
            resolvedAt: order.disputeStatus === 'OPEN' ? null : row.paidAt + 20 * DAY,
          },
        ]
      : [];

  // ---- deliveries (one per digital line, plus the revoke of a refunded order)
  const deliveries = [];
  if (paid) {
    items
      .filter((item) => !item.physical)
      .forEach((item, at) => {
        const status = deliveryStatusOf(row, random);
        const credit = item.kind === 'CREDIT_TOPUP';
        const type = credit ? 'CREDIT' : at % 2 === 0 ? 'COMMAND' : 'PERMISSION';
        const done = status === 'CONFIRMED';
        const grant = {
          id: item.id * 10 + 1,
          orderId: row.id,
          orderItemId: item.id,
          productName: item.productName,
          playerUsername: row.recipientUsername ?? row.playerUsername,
          phase: 'GRANT',
          actionId: `a${at + 1}`,
          actionType: type,
          transport: credit ? 'INLINE' : 'MARKET_MC',
          idempotencyKey: `d-${row.publicId.slice(0, 8)}-${item.id}-0`,
          serverId: item.targetServerId,
          serverName: item.targetServerName,
          status,
          attempts: status === 'FAILED' ? 5 : done ? 1 : 0,
          requiresOnline: type === 'COMMAND',
          waitUntil: status === 'WAITING_PLAYER' ? row.paidAt + 30 * DAY : null,
          cancelRequested: false,
          lastErrorCode:
            status === 'FAILED'
              ? 'COMMAND_FAILED'
              : status === 'WAITING_SERVER'
                ? 'SERVER_OFFLINE'
                : null,
          lastError:
            status === 'FAILED'
              ? 'Unknown command. Type "/help" for help. (lp user ... parent add)'
              : null,
          runAfter: null,
          sentAt: done || status === 'FAILED' ? row.paidAt + 3000 : null,
          confirmedAt: done ? row.paidAt + 4500 : null,
          payload: credit
            ? { amount: money(item.lineTotal * 100) }
            : type === 'COMMAND'
              ? {
                  commands: [
                    `lp user ${row.playerUsername} parent add vip`,
                    `broadcast ${row.playerUsername} just bought ${item.productName}!`,
                  ],
                }
              : { permissions: [PERMISSIONS[item.productId % PERMISSIONS.length]] },
          result: done ? { ok: true, executed: type === 'COMMAND' ? 2 : 1 } : null,
        };
        deliveries.push(grant);
        if (row.fulfillmentStatus === 'REVOKED' && !credit) {
          deliveries.push({
            ...grant,
            id: grant.id + 1,
            phase: 'REVOKE',
            idempotencyKey: `${grant.idempotencyKey}-r`,
            status: 'CONFIRMED',
            attempts: 1,
            sentAt: row.paidAt + 2 * DAY + 6000,
            confirmedAt: row.paidAt + 2 * DAY + 7000,
            payload:
              type === 'COMMAND'
                ? { commands: [`lp user ${row.playerUsername} parent remove vip`] }
                : grant.payload,
            result: { ok: true, executed: 1 },
          });
        }
      });
  }

  // ---- shipments
  const shipmentStatus = {
    PARTIAL: 'LABEL_READY',
    SHIPPED: 'IN_TRANSIT',
    DELIVERED: 'DELIVERED',
    RETURNED: 'RETURNED',
  }[row.shippingStatus];
  const shipments =
    paid && shipmentStatus
      ? [
          {
            id: row.id + 600,
            orderId: row.id,
            status: shipmentStatus,
            entryMode: row.id % 2 ? 'MANUAL' : 'CARRIER',
            providerId: row.id % 2 ? null : 'pano-plugin-market-shipping-demo',
            carrierName: row.id % 2 ? 'DHL Express' : 'Demo Carrier',
            serviceCode: 'STANDARD',
            trackingNumber: `1Z${String(row.id).padStart(6, '0')}A${row.id * 7919}`,
            trackingUrl: `https://tracking.example.com/${row.id * 7919}`,
            labelFile: row.id % 2 ? null : `label-${row.id}.pdf`,
            cost: 6.5,
            costCurrency: row.currency,
            toAddress: {
              firstName: address.firstName,
              lastName: address.lastName,
              city: address.city,
              country: address.country,
            },
            stale: false,
            lastErrorCode: null,
            lastError: null,
            note: row.id % 3 === 0 ? 'Fragile: collector figurine' : null,
            estimatedDeliveryAt: row.paidAt + 5 * DAY,
            shippedAt: shipmentStatus === 'LABEL_READY' ? null : row.paidAt + DAY,
            deliveredAt: shipmentStatus === 'DELIVERED' ? row.paidAt + 4 * DAY : null,
            createdAt: row.paidAt + 6 * HOUR,
          },
        ]
      : [];

  // ---- timeline (newest first)
  const events = [];
  const event = (type, at, extra = {}) =>
    events.push({
      type,
      fromStatus: null,
      toStatus: null,
      actorType: 'SYSTEM',
      actorUsername: null,
      message: null,
      createdAt: at,
      ...extra,
    });
  event('CREATED', row.createdAt, {
    actorType: row.source === 'PANEL' ? 'ADMIN' : 'BUYER',
    actorUsername: row.source === 'PANEL' ? actor : null,
    toStatus: 'PENDING',
  });
  event('PAYMENT_STARTED', row.createdAt + 15000, { actorType: 'BUYER' });
  if (row.id % 7 === 0)
    event('PAYMENT_FAILED', row.createdAt + 9000, {
      actorType: 'GATEWAY',
      message: 'card_declined',
    });
  if (row.status === 'FAILED')
    event('PAYMENT_FAILED', row.createdAt + 40000, {
      actorType: 'GATEWAY',
      fromStatus: 'PENDING',
      toStatus: 'FAILED',
    });
  if (row.status === 'CANCELLED')
    event('PAYMENT_CANCELLED', row.createdAt + HOUR, {
      actorType: 'BUYER',
      fromStatus: 'PENDING',
      toStatus: 'CANCELLED',
    });
  if (row.status === 'EXPIRED')
    event('STATUS_CHANGED', row.createdAt + DAY, { fromStatus: 'PENDING', toStatus: 'EXPIRED' });
  if (paid) {
    event('PAYMENT_SUCCEEDED', row.paidAt, {
      actorType: 'GATEWAY',
      fromStatus: 'PENDING',
      toStatus: row.status === 'REVIEW' ? 'REVIEW' : 'COMPLETED',
    });
    event('MAIL_QUEUED', row.paidAt + 1000, { message: 'ORDER_CONFIRMATION' });
    if (row.status === 'REVIEW')
      event('REVIEW_OPENED', row.paidAt + 500, { message: row.reviewReason });
    if (row.fulfillmentStatus === 'FAILED')
      event('DELIVERY_FAILED', row.paidAt + 5 * MINUTE, { message: 'COMMAND_FAILED' });
    if (subscriptionId) event('SUBSCRIPTION_STARTED', row.paidAt + 800);
    if (shipments.length)
      event('SHIPMENT_CREATED', row.paidAt + 6 * HOUR, {
        actorType: 'ADMIN',
        actorUsername: actor,
      });
    if (order.note)
      event('NOTE', row.paidAt + 8 * HOUR, { actorType: 'ADMIN', actorUsername: actor });
    if (refunds.length) {
      event('REFUND_REQUESTED', row.paidAt + 2 * DAY, {
        actorType: 'ADMIN',
        actorUsername: actor,
        message: refunds[0].reason,
      });
      event('REFUND_SUCCEEDED', row.paidAt + 2 * DAY + 4000, {
        actorType: 'GATEWAY',
        fromStatus: 'COMPLETED',
        toStatus: row.status,
      });
      if (row.fulfillmentStatus === 'REVOKED')
        event('DELIVERY_REVOKED', row.paidAt + 2 * DAY + 7000);
    }
    if (disputes.length) {
      event('DISPUTE_OPENED', disputes[0].openedAt, {
        actorType: 'GATEWAY',
        fromStatus: 'COMPLETED',
        toStatus: 'CHARGEBACK',
        message: 'fraudulent',
      });
      event('BLOCK_CREATED', disputes[0].openedAt + 1000, {
        message: `PLAYER ${row.playerUsername}`,
      });
      if (disputes[0].resolvedAt)
        event('DISPUTE_CLOSED', disputes[0].resolvedAt, {
          actorType: 'GATEWAY',
          message: disputes[0].status,
        });
    }
  }
  events.sort((a, b) => a.createdAt - b.createdAt);
  events.forEach((e, at) => (e.id = row.id * 100 + at + 1));
  events.reverse();
  const timeline = events.map(({ id, ...rest }) => ({ id, ...rest }));

  const invoices = paid
    ? [
        {
          id: row.id + 100,
          type: 'INVOICE',
          refundId: null,
          number: `INV-2026-${String(row.id).padStart(6, '0')}`,
          issuedAt: row.paidAt,
        },
        ...refunds.map((refund) => ({
          id: row.id + 101,
          type: 'CREDIT_NOTE',
          refundId: refund.id,
          number: `CN-2026-${String(row.id).padStart(6, '0')}`,
          issuedAt: refund.completedAt,
        })),
      ]
    : [];

  const mail = (at, kind, status, when) => ({
    id: row.id * 10 + at,
    kind,
    recipient: row.email,
    status,
    attempts: status === 'FAILED' ? 3 : status === 'PENDING' ? 0 : 1,
    lastError: status === 'FAILED' ? `550 5.1.1 <${row.email}>: Recipient address rejected` : null,
    createdAt: when,
    sentAt: status === 'SENT' ? when + 2000 : null,
  });
  const mails = [mail(1, 'ORDER_RECEIVED', 'SENT', row.createdAt + 1000)];
  if (paid)
    mails.push(
      mail(2, 'ORDER_CONFIRMATION', row.id % 11 === 0 ? 'FAILED' : 'SENT', row.paidAt + 1000),
    );
  if (row.fulfillmentStatus === 'FULFILLED')
    mails.push(mail(3, 'ORDER_DELIVERED', 'SENT', row.paidAt + 6000));
  if (refunds.length) mails.push(mail(4, 'ORDER_REFUNDED', 'SENT', refunds[0].completedAt));
  if (row.paymentMethodId === 'bank-transfer' && row.status === 'PENDING')
    mails.push(mail(5, 'BANK_TRANSFER_INSTRUCTIONS', 'PENDING', row.createdAt + 2000));

  const subscription = subscriptionId
    ? {
        id: subscriptionId,
        playerUsername: row.playerUsername,
        productName: subscriptionItem.productName,
        status: row.status === 'COMPLETED' ? 'ACTIVE' : 'CANCELLED',
        mode: 'MERCHANT',
        providerId: method.providerId,
        price: subscriptionItem.lineTotal,
        currency: row.currency,
        intervalUnit: 'MONTH',
        intervalCount: 1,
        cycleCount: 1,
        maxCycles: null,
        currentPeriodStart: row.paidAt,
        currentPeriodEnd: row.paidAt + 30 * DAY,
        nextChargeAt: row.status === 'COMPLETED' ? row.paidAt + 30 * DAY : null,
        graceEndsAt: null,
        cancelAtPeriodEnd: false,
        failCount: 0,
        endReason: row.status === 'COMPLETED' ? null : 'REFUNDED',
        remoteCancelState: 'NONE',
        gatewaySubscriptionId: null,
        storedMethodLabel: method.id === 'stripe' ? 'Visa •••• 4242' : method.label,
        testMode: row.testMode,
        createdAt: row.paidAt,
      }
    : null;

  const refundable = PAID.has(row.status) && row.totalPrice - row.refundedTotal > 0;
  const grants = deliveries.filter((d) => d.phase === 'GRANT');
  const bank = row.paymentMethodId === 'bank-transfer';
  const allowed = {
    markPaid: row.status === 'PENDING',
    cancel: row.status === 'PENDING',
    refund: refundable,
    refundMax: refundable ? money(row.totalPrice - row.refundedTotal) : 0,
    refundModes: refundable ? ['FULL', 'PARTIAL', 'PER_LINE', 'MANUAL'] : [],
    review: row.status === 'REVIEW',
    bankTransfer: bank && UNPAID.has(row.status),
    dispute: PAID.has(row.status) || row.status === 'REFUNDED',
    rerunDelivery:
      PAID.has(row.status) && grants.some((d) => d.status === 'FAILED' || d.status === 'CONFIRMED'),
    revoke:
      paid && row.fulfillmentStatus !== 'REVOKED' && grants.some((d) => d.status === 'CONFIRMED'),
    createShipment:
      PAID.has(row.status) &&
      physical &&
      items.some((i) => i.physical && i.quantity - i.refundedQuantity - i.shippedQuantity > 0),
    editShippingAddress:
      physical &&
      !['EXPIRED', 'CANCELLED', 'FAILED', 'REFUNDED'].includes(row.status) &&
      shipments.length === 0,
    resendMail: paid || row.status === 'PENDING',
    anonymize: row.status !== 'PENDING' && row.status !== 'REVIEW',
    runChargebackActions: false,
  };

  return ok({
    order,
    items,
    payments,
    refunds,
    disputes,
    deliveries,
    shipments,
    events: timeline,
    invoices,
    mails,
    subscription,
    revokePending: 0,
    revokeFailed: 0,
    allowed,
  });
}

/** GET /orders/:id; a non-numeric id is not ours (another route, e.g. an export), unknown = 404. */
export function orderDetail(volume, rawId) {
  const id = idOf(rawId);
  if (id === null) return undefined;
  const row = orderRows(volume).find((order) => order.id === id);
  return row ? buildOrderDetail(row, volume) : NOT_FOUND;
}

/** GET /payments/:paymentId/events: the inbound / outbound gateway traffic of one attempt. */
function paymentEvents({ params, query, volume }) {
  const paymentId = idOf(params.paymentId);
  if (paymentId === null) return NOT_FOUND;
  const row = orderRows(volume).find(
    (order) => order.id * 3 === paymentId || order.id * 3 - 1 === paymentId,
  );
  if (!row) return NOT_FOUND;
  const method = PAYMENT_METHODS.find((m) => m.id === row.paymentMethodId) ?? PAYMENT_METHODS[0];
  const failed = paymentId !== row.id * 3 || row.status === 'FAILED';
  const steps = [
    ['OUTBOUND', 'API', ['checkout.create'], 200],
    ['INBOUND', 'RETURN', ['return'], 302],
    [
      'INBOUND',
      'WEBHOOK',
      [failed ? 'payment_intent.payment_failed' : 'payment_intent.succeeded', 'charge.updated'],
      200,
    ],
    ['INBOUND', 'WEBHOOK', ['charge.updated'], 200],
  ];
  const events = steps
    .map(([direction, channel, eventTypes, responseStatus], at) => ({
      id: paymentId * 10 + at,
      providerId: method.providerId,
      direction,
      channel,
      eventKey:
        channel === 'WEBHOOK' ? `evt_${row.publicId.slice(0, 13).replace(/-/g, '')}${at}` : null,
      paymentId,
      orderId: row.id,
      verified: direction === 'INBOUND' ? true : null,
      status: at === 3 ? 'DUPLICATE' : 'PROCESSED',
      eventTypes,
      responseStatus,
      error: null,
      remoteIp: direction === 'INBOUND' ? '198.51.100.24' : null,
      attempts: 1,
      duplicateCount: at === 3 ? 2 : 0,
      createdAt: row.createdAt + 15000 + at * 9000,
    }))
    .reverse();
  return listBody('events', 'eventCount', events, query, PAGE_SIZE);
}

// ================================================================================== overview

/** Deterministic daily revenue (decimal, stats currency) of day `ago` days before NOW. */
function dayRevenue(volume, ago) {
  if (volume === 'empty') return { count: 0, revenue: 0 };
  const random = rng(`stats:${volume}:${ago}`);
  const scale = volume === 'many' ? 9 : 1;
  const weekend = [0, 6].includes(new Date(NOW - ago * DAY).getUTCDay()) ? 1.6 : 1;
  const count = Math.floor(random() * 6 * scale * weekend);
  return { count, revenue: money(count * (6 + random() * 18)) };
}

const sum = (list) => money(list.reduce((total, value) => total + value, 0));
const trendOf = (current, previous) =>
  previous === 0
    ? current > 0
      ? 100
      : 0
    : Math.round(((current - previous) / previous) * 10000) / 100;
const pad2 = (n) => String(n).padStart(2, '0');

function isoWeekKey(ms) {
  const d = new Date(ms);
  d.setUTCDate(d.getUTCDate() + 4 - (d.getUTCDay() || 7));
  const yearStart = Date.UTC(d.getUTCFullYear(), 0, 1);
  return `${d.getUTCFullYear()}${pad2(Math.ceil(((d - yearStart) / DAY + 1) / 7))}`;
}

export function statsBody(volume, query = {}) {
  const days = Array.from({ length: 60 }, (_, i) => dayRevenue(volume, 59 - i)); // oldest first
  const block = (from, to, prevFrom, prevTo) => {
    const revenue = sum(days.slice(from, to).map((d) => d.revenue));
    const previous = sum(days.slice(prevFrom, prevTo).map((d) => d.revenue));
    return {
      count: days.slice(from, to).reduce((n, d) => n + d.count, 0),
      revenue,
      previous,
      trend: trendOf(revenue, previous),
      spark: days.slice(from, to).map((d) => d.revenue),
    };
  };
  const weekly = block(53, 60, 46, 53);
  const monthly = block(30, 60, 0, 30);

  // `from` / `to` bound the total, the top products, the payment methods, the currencies, the refunds
  const from = numberOf(query.from);
  const to = numberOf(query.to);
  const span =
    from !== null || to !== null
      ? Math.max(1, Math.min(365, Math.ceil(((to ?? NOW) - (from ?? NOW - 365 * DAY)) / DAY)))
      : 365;
  const ranged = Array.from({ length: span }, (_, i) => dayRevenue(volume, i));
  const totalRevenue = sum(ranged.map((d) => d.revenue));
  const totalCount = ranged.reduce((n, d) => n + d.count, 0);

  const weekLabels = Array.from({ length: 8 }, (_, i) => isoWeekKey(NOW - (7 - i) * 7 * DAY));
  const weekValues = weekLabels.map((_, i) =>
    sum(Array.from({ length: 7 }, (_, d) => dayRevenue(volume, (7 - i) * 7 + d).revenue)),
  );
  const now = new Date(NOW);
  const months = Array.from(
    { length: 6 },
    (_, i) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (5 - i), 1)),
  );
  const monthValues = months.map((_, i) =>
    money(monthly.revenue * (0.55 + 0.09 * i) + (volume === 'empty' ? 0 : i * 3.5)),
  );

  const some = volume !== 'empty';
  const products = productRows(volume).slice(0, 5);
  const shares = [0.34, 0.24, 0.18, 0.14, 0.1];
  const methodShares = [0.55, 0.27, 0.1, 0.08];
  const currencyShares = [
    ['USD', 0.5, 1],
    ['EUR', 0.25, 0.9],
    ['TRY', 0.15, 40],
    ['GBP', 0.1, 0.8],
  ];

  return ok({
    summary: {
      weekly,
      monthly,
      total: {
        count: totalCount,
        revenue: totalRevenue,
        previous: 0,
        trend: 0,
        spark: monthly.spark,
      },
      refunds: {
        count: some ? Math.max(1, Math.round(totalCount * 0.04)) : 0,
        amount: money(totalRevenue * 0.035),
      },
      activeSubscriptions: some ? countFor(volume, 3, 41) : 0,
    },
    charts: {
      weeklyRevenue: { labels: weekLabels, values: weekValues },
      monthlyRevenue: {
        labels: months.map((m) => `${m.getUTCFullYear()}-${pad2(m.getUTCMonth() + 1)}`),
        values: monthValues,
      },
      topProducts: some
        ? {
            labels: products.map((p) => p.name),
            values: products.map((_, i) => money(totalRevenue * 0.8 * shares[i])),
          }
        : { labels: [], values: [] },
      paymentMethods: some
        ? {
            labels: PAYMENT_METHODS.map((m) => m.label),
            values: methodShares.map((share) => Math.max(1, Math.round(totalCount * share))),
          }
        : { labels: [], values: [] },
      currencies: some
        ? {
            labels: currencyShares.map(([code]) => code),
            values: currencyShares.map(([, share, rate]) => money(totalRevenue * share * rate)),
          }
        : { labels: [], values: [] },
    },
    statsCurrency: STORE_CURRENCY,
    statsCurrencySymbol: '$',
  });
}

const SERVER_STATES = ['READY', 'READY', 'VERSION_MISMATCH', 'OFFLINE'];

/** GET /servers is answered by panel-lists.js; the health page shows the same servers. */
function listedServers(volume) {
  const route = listRoutes.find((r) => r.method === 'GET' && r.path === `${API}/servers`);
  const body = route?.handler({ query: {}, params: {}, volume });
  return Array.isArray(body?.servers) ? body.servers : serverRows(volume);
}

/** Fallback server rows (McServerView.toJson) when the list fixtures carry no /servers. */
export function serverRows(volume) {
  const list = volume === 'empty' ? [] : volume === 'few' ? SERVERS.slice(0, 2) : SERVERS;
  return list.map((server, at) => {
    const marketState = volume === 'few' ? (at === 1 ? 'OFFLINE' : 'READY') : SERVER_STATES[at];
    const proxy = server.id === 4;
    return {
      id: server.id,
      name: server.name,
      type: proxy ? 'BUNGEECORD' : 'PAPER',
      connected: marketState !== 'OFFLINE',
      proxy,
      mcComponentVersion: marketState === 'VERSION_MISMATCH' ? '1.0.0-alpha.61' : '1.0.0-alpha.65',
      requiredVersion: '1.0.0-alpha.65',
      marketState,
      waitingDeliveries: marketState === 'READY' ? 0 : 3 + at,
      queuedDeliveries: marketState === 'READY' ? at : 0,
      downloadUrl: `${API}/servers/${server.id}/mc-component`,
      platform: proxy ? 'BUNGEECORD' : 'SPIGOT',
      integrations: proxy ? [] : ['LUCKPERMS', 'VAULT', 'PLACEHOLDER_API'],
      settings:
        at === 0
          ? { broadcastPurchases: true, broadcastFormat: '&6{player} &fjust bought &a{product}&f!' }
          : null,
    };
  });
}

const JOBS = [
  'housekeeping',
  'delivery-dispatch',
  'mail-outbox',
  'webhook-outbox',
  'subscription-billing',
  'exchange-rates',
  'shipment-tracking',
];

export function healthBody(volume) {
  const busy = volume === 'many';
  return ok({
    runtimeState: 'READY',
    schema: { ok: true, missing: [], unfixed: [] },
    bootstrapErrors: [],
    jobs: JOBS.map((name, at) => ({
      name,
      lastRunAt: NOW - (at + 1) * 20000,
      lagSeconds: busy && at === 5 ? 5400 : 0,
      lastError:
        busy && at === 5 ? 'ExchangeRateException: provider answered 503 (attempt 4 of 5)' : null,
    })),
    queues: {
      deliveriesPending: busy ? 14 : volume === 'few' ? 2 : 0,
      deliveriesFailed: busy ? 3 : 0,
      mailsPending: busy ? 6 : 0,
      webhooksPending: busy ? 2 : 0,
      deferredEvents: busy ? 1 : 0,
      failedEvents: busy ? 2 : 0,
    },
    providers: [
      { id: 'bank-transfer', state: 'AVAILABLE' },
      { id: 'pano-plugin-market-paypal', state: busy ? 'UNAVAILABLE' : 'AVAILABLE' },
      { id: 'pano-plugin-market-stripe', state: 'AVAILABLE' },
    ],
    servers: listedServers(volume).map((s) => ({
      id: s.id,
      marketState: s.marketState,
      waitingDeliveries: s.waitingDeliveries,
    })),
    credits: busy
      ? {
          ok: false,
          checkedAt: NOW - 3 * HOUR,
          problems: ['ACCOUNT_BALANCE_MISMATCH account=7 stored=1250 ledger=1200'],
        }
      : { ok: true, checkedAt: NOW - 3 * HOUR, problems: [] },
    mail: 'OK',
    mailEnabled: true,
    ipTrust: 'DIRECT',
    lockedSubjects: busy ? 2 : 0,
    rejectedEventsLastHour: busy ? 5 : 0,
    routes: [],
  });
}

// ================================================================================== credits

const TX_TYPES = [
  ['TOPUP', 6],
  ['CAPTURE', 6],
  ['GRANT', 3],
  ['REVOKE', 1],
  ['REFUND', 2],
  ['CASHBACK', 2],
  ['GIFT', 1],
  ['ACTION', 1],
  ['CREATOR_PAYOUT', 1],
  ['HOLD', 1],
  ['RELEASE', 1],
];
const OUT = new Set(['CAPTURE', 'REVOKE', 'HOLD']);
const TX_NOTES = {
  GRANT: 'Compensation for the rollback on 12 September',
  REVOKE: 'Granted twice by mistake',
  GIFT: 'Gift code HALLOWEEN26',
};

/** The global ledger, newest first. Amounts are credits (decimal), signed from the player's side. */
export function creditTransactions(volume) {
  const n = countFor(volume, 9, 137);
  const orders = orderRows(volume);
  return rows('credit-tx', n, (i, random) => {
    const type = weighted(random(), TX_TYPES);
    const userId = 1 + Math.floor(random() * PLAYERS.length);
    const amount = money((OUT.has(type) ? -1 : 1) * (50 + Math.floor(random() * 40) * 25));
    const withOrder =
      ['TOPUP', 'CAPTURE', 'REFUND', 'CASHBACK', 'HOLD', 'RELEASE'].includes(type) && orders.length;
    const manual = type === 'GRANT' || type === 'REVOKE';
    return {
      id: 9000 + n - i,
      type,
      userId,
      username: PLAYERS[userId - 1],
      amount,
      shortfall: type === 'REVOKE' && i % 2 === 0 ? 75 : 0,
      orderId: withOrder ? orders[Math.floor(random() * orders.length)].id : null,
      refundId: type === 'REFUND' ? 300 + i : null,
      deliveryId: type === 'ACTION' ? 40000 + i : null,
      actorUsername: manual ? ADMINS[i % ADMINS.length] : null,
      note: TX_NOTES[type] ?? null,
      createdAt: NOW - i * 5 * HOUR - Math.floor(random() * 4 * HOUR),
    };
  });
}

/** Entries of one account, newest first, with the running balance (never below zero). */
function accountEntries(volume, userId) {
  const own = creditTransactions(volume)
    .filter((tx) => tx.userId === userId)
    .reverse(); // oldest first
  let balance = 0;
  return own
    .map((tx) => {
      const amount = tx.amount < 0 ? -Math.min(balance, -tx.amount) : tx.amount;
      balance = money(balance + amount);
      return {
        id: tx.id * 2,
        type: tx.type,
        amount,
        balanceAfter: balance,
        shortfall: tx.shortfall,
        note: tx.note,
        orderId: tx.orderId,
        refundId: tx.refundId,
        deliveryId: tx.deliveryId,
        actorUsername: tx.actorUsername,
        createdAt: tx.createdAt,
      };
    })
    .reverse();
}

export function creditAccounts(volume) {
  const users = new Set(creditTransactions(volume).map((tx) => tx.userId));
  return [...users]
    .sort((a, b) => a - b)
    .map((userId) => ({
      userId,
      username: PLAYERS[userId - 1],
      balance: accountEntries(volume, userId)[0]?.balanceAfter ?? 0,
    }))
    .sort((a, b) => b.balance - a.balance || a.userId - b.userId);
}

function creditAccountsBody({ query, volume }) {
  const all = creditAccounts(volume);
  const body = listBody(
    'accounts',
    'accountCount',
    all.filter((a) => matches(query.search, a.username)),
    query,
    PAGE_SIZE,
  );
  if (body.result !== 'ok') return body;
  const outstanding = sum(all.map((a) => a.balance));
  const txs = creditTransactions(volume);
  const total = (types) =>
    sum(txs.filter((tx) => types.includes(tx.type)).map((tx) => Math.abs(tx.amount)));
  return {
    ...body,
    totals: {
      issued: money(outstanding + total(['CAPTURE']) + total(['REVOKE'])),
      spent: total(['CAPTURE']),
      held: total(['HOLD']),
      revoked: total(['REVOKE']),
      external: 0,
      outstanding,
    },
  };
}

function creditAccountBody({ params, query, volume }) {
  const userId = idOf(params.userId);
  if (userId === null || userId < 1 || userId > PLAYERS.length) return NOT_FOUND;
  const entries = accountEntries(volume, userId);
  const body = listBody('entries', 'entryCount', entries, query, PAGE_SIZE);
  return body.result === 'ok'
    ? { result: 'ok', balance: entries[0]?.balanceAfter ?? 0, ...body }
    : body;
}

function creditTransactionsBody({ query, volume }) {
  const userId = numberOf(query.userId);
  const orderId = numberOf(query.orderId);
  const from = numberOf(query.from);
  const to = numberOf(query.to);
  const list = creditTransactions(volume).filter(
    (tx) =>
      (!query.type || tx.type === query.type) &&
      (userId === null || tx.userId === userId) &&
      (orderId === null || tx.orderId === orderId) &&
      (from === null || tx.createdAt >= from) &&
      (to === null || tx.createdAt < to),
  );
  return listBody('transactions', 'transactionCount', list, query, PAGE_SIZE);
}

// ================================================================================== blocks

const BLOCK_REASONS = [
  'Chargeback on order',
  'Stolen card reported by the bank',
  'Repeated refund abuse',
  null,
  'Alt account of a banned player (see ticket #4821, shared IP and the same payment fingerprint)',
];

export function blockRows(volume) {
  const n = countFor(volume, 6, 47);
  const orders = orderRows(volume);
  return rows('block', n, (i, random) => {
    const type = weighted(random(), [
      ['PLAYER', 5],
      ['EMAIL', 3],
      ['IP', 3],
      ['USER', 1],
    ]);
    const player = PLAYERS[Math.floor(random() * PLAYERS.length)];
    const chargeback = random() < 0.35 && orders.length > 0;
    const value =
      type === 'PLAYER'
        ? player
        : type === 'USER'
          ? String(1 + PLAYERS.indexOf(player))
          : type === 'EMAIL'
            ? i % 4 === 0
              ? '*@tempmail.example'
              : `${player.toLowerCase().replace(/[^a-z0-9]+/g, '.')}@example.com`
            : i % 3 === 0
              ? `198.51.${100 + (i % 50)}.0/24`
              : `203.0.113.${1 + Math.floor(random() * 250)}`;
    const hits = Math.floor(random() * random() * 40);
    return {
      id: 500 + n - i,
      type,
      value,
      reason: chargeback ? 'Chargeback on order' : BLOCK_REASONS[i % BLOCK_REASONS.length],
      source: chargeback ? 'CHARGEBACK' : 'MANUAL',
      orderId: chargeback ? orders[Math.floor(random() * orders.length)].id : null,
      createdBy: chargeback ? null : 1 + (i % ADMINS.length),
      createdByUsername: chargeback ? null : ADMINS[i % ADMINS.length],
      hitCount: hits,
      lastHitAt: hits ? NOW - Math.floor(random() * 20 * DAY) : null,
      expiresAt: i % 5 === 2 ? NOW + (30 - i) * DAY : i % 11 === 4 ? NOW - 2 * DAY : null,
      createdAt: NOW - (i + 1) * 3 * DAY,
    };
  });
}

function blocksBody({ query, volume }) {
  const prefix = String(query.search ?? '')
    .trim()
    .toLowerCase();
  const list = blockRows(volume).filter(
    (b) =>
      (!query.type || b.type === query.type) &&
      (!query.source || b.source === query.source) &&
      (!prefix || b.value.toLowerCase().startsWith(prefix)),
  );
  return listBody('blocks', 'blockCount', list, query, PAGE_SIZE);
}

// ================================================================================== promotions

const window = (i, random) => {
  const roll = random();
  const startDate = roll < 0.3 ? null : NOW - Math.floor(random() * 60) * DAY;
  const expiryDate =
    roll < 0.3 ? null : i % 5 === 3 ? NOW - 3 * DAY : NOW + (5 + Math.floor(random() * 80)) * DAY;
  return { startDate, expiryDate };
};
const statusOf = (random) =>
  weighted(random(), [
    ['ACTIVE', 4],
    ['INACTIVE', 1],
  ]);
const stamps = (i, random) => {
  const createdAt = NOW - (i + 2) * 4 * DAY;
  return { createdAt, updatedAt: createdAt + Math.floor(random() * 3 * DAY) };
};

const DISCOUNT_NAMES = [
  'Halloween Sale',
  'Rank Week',
  'Back to School',
  'Weekend Flash Sale',
  'Black Friday - everything in the store, ranks, crates and cosmetics included',
  'Server Birthday',
  'Summer Splash',
];

export function discountRows(volume) {
  const n = countFor(volume, 5, 34);
  const products = productRows(volume);
  const categories = categoryRows(volume);
  return rows('discount', n, (i, random) => {
    const scope = products.length
      ? weighted(random(), [
          ['ALL', 2],
          ['PRODUCTS', 3],
          ['CATEGORIES', 2],
        ])
      : 'ALL';
    const unit = random() < 0.7 ? 'PERCENT' : 'FIXED';
    const productIds =
      scope === 'PRODUCTS'
        ? products.slice(i % products.length, (i % products.length) + 1 + (i % 3)).map((p) => p.id)
        : null;
    const categoryIds =
      scope === 'CATEGORIES'
        ? categories
            .slice(i % categories.length, (i % categories.length) + 1 + (i % 2))
            .map((c) => c.id)
        : null;
    const usageLimit = i % 3 === 0 ? 100 + i * 10 : null;
    const round = Math.floor(i / DISCOUNT_NAMES.length);
    return {
      id: 200 + n - i,
      name: DISCOUNT_NAMES[i % DISCOUNT_NAMES.length] + (round ? ` ${2026 - round}` : ''),
      value: unit === 'PERCENT' ? [10, 15, 20, 25, 50][i % 5] : [2, 5, 7.5][i % 3],
      unit,
      minPaymentAmount: i % 4 === 1 ? 20 : null,
      scope,
      productIds,
      categoryIds,
      ...window(i, random),
      usageLimit,
      usedCount: Math.floor(random() * (usageLimit ?? 400)),
      showBadge: i % 3 !== 2,
      status: statusOf(random),
      ...stamps(i, random),
      products:
        scope === 'PRODUCTS'
          ? productIds.map((id) => products[id - 1].name)
          : scope === 'CATEGORIES'
            ? categoryIds.map((id) => categories[id - 1].name)
            : ['all'],
    };
  });
}

const COUPON_CODES = [
  'WELCOME10',
  'HALLOWEEN26',
  'SKYBLOCK5',
  'VIPUPGRADE',
  'DISCORD-BOOSTER-THANK-YOU-2026',
  'FIRSTORDER',
  'COMEBACK15',
  'STREAM20',
];

export function couponRows(volume) {
  const n = countFor(volume, 6, 58);
  const products = productRows(volume);
  const categories = categoryRows(volume);
  return rows('coupon', n, (i, random) => {
    const scope = products.length
      ? weighted(random(), [
          ['ALL', 4],
          ['PRODUCTS', 2],
          ['CATEGORIES', 1],
        ])
      : 'ALL';
    const unit = random() < 0.65 ? 'PERCENT' : 'FIXED';
    const round = Math.floor(i / COUPON_CODES.length);
    const code = COUPON_CODES[i % COUPON_CODES.length] + (round ? `-${round + 1}` : '');
    const redeemLimit = i % 2 === 0 ? 50 + i * 5 : null;
    return {
      id: 300 + n - i,
      name: i % 3 === 0 ? `Campaign: ${code.toLowerCase()}` : '',
      code,
      scope,
      productIds: scope === 'PRODUCTS' ? [products[i % products.length].id] : null,
      categoryIds: scope === 'CATEGORIES' ? [categories[i % categories.length].id] : null,
      discount: unit === 'PERCENT' ? [5, 10, 15, 20][i % 4] : [1, 2.5, 5][i % 3],
      unit,
      minPaymentAmount: i % 5 === 2 ? 10 : null,
      ...window(i, random),
      redeemLimit,
      customerRedeemLimit: i % 3 === 1 ? null : 1,
      usedCount: Math.floor(random() * (redeemLimit ?? 300)),
      status: statusOf(random),
      ...stamps(i, random),
    };
  });
}

const CREATORS = [
  'DreamCraft',
  'PixelPaladinTV',
  'RedstoneWizard',
  'TheVeryLongCreatorNameOfAStreamerWhoPlaysSkyblock',
  'EnderQueen',
  'BlockBuster_YT',
  'NetherNomad',
];

export function creatorRows(volume) {
  const n = countFor(volume, 4, 26);
  return rows('creator', n, (i, random) => {
    const round = Math.floor(i / CREATORS.length);
    const creator = CREATORS[i % CREATORS.length] + (round ? `_${round + 1}` : '');
    const linked = PLAYERS.indexOf(creator);
    const usedCount = Math.floor(random() * 220);
    const earnings = money(usedCount * (0.8 + random() * 1.4));
    return {
      id: n - i,
      creator,
      creatorUserId: linked >= 0 ? linked + 1 : i % 2 === 0 ? 20 + i : null,
      code: creator
        .replace(/[^A-Za-z0-9]/g, '')
        .toUpperCase()
        .slice(0, 16),
      discount: [5, 10, 0, 15][i % 4],
      unit: 'PERCENT',
      commissionPercent: [10, 12.5, 20, 5][i % 4],
      ...window(i, random),
      redeemLimit: i % 4 === 3 ? 500 : null,
      usedCount,
      earnings,
      paidOut: money(earnings * [0.5, 0, 0.8, 1][i % 4]),
      status: statusOf(random),
      ...stamps(i, random),
    };
  });
}

/** The earnings of a creator code, newest first; their sums back the report row. */
export function creatorEarnings(volume, code) {
  const orders = orderRows(volume);
  const n = Math.min(code.usedCount, volume === 'many' ? 64 : 9);
  return rows(`earning:${code.id}`, orders.length ? n : 0, (i, random) => {
    const baseAmount = money(5 + random() * 60);
    const amount = money((baseAmount * code.commissionPercent) / 100);
    const state = weighted(random(), [
      ['AVAILABLE', 5],
      ['PENDING', 2],
      ['PAID', 4],
      ['REVERSED', 1],
    ]);
    const createdAt = NOW - (i + 1) * 2 * DAY - Math.floor(random() * DAY);
    return {
      id: code.id * 1000 + n - i,
      orderId: orders[Math.floor(random() * orders.length)].id,
      baseAmount,
      commissionPercent: code.commissionPercent,
      amount,
      reversedAmount: state === 'REVERSED' ? amount : 0,
      state,
      availableAt: createdAt + 14 * DAY,
      createdAt,
    };
  });
}

export function creatorPayouts(volume, code) {
  const paid = sum(
    creatorEarnings(volume, code)
      .filter((e) => e.state === 'PAID')
      .map((e) => e.amount),
  );
  if (paid <= 0) return [];
  const parts = [0.6, 0.4];
  const list = parts.map((share, i) => ({
    id: code.id * 100 + 2 - i,
    amount: i === 0 ? money(paid - money(paid * parts[1])) : money(paid * share),
    currency: STORE_CURRENCY,
    method: ['CREDIT', 'MANUAL', 'ACTION'][(code.id + i) % 3],
    state: 'PAID',
    note: (code.id + i) % 3 === 1 ? 'Bank transfer, reference PANO-PAYOUT-2026-09' : null,
    paidBy: ADMINS[i % ADMINS.length],
    paidAt: NOW - (10 + i * 30) * DAY,
    createdAt: NOW - (10 + i * 30) * DAY - HOUR,
  }));
  if (code.id % 2 === 0) {
    list.unshift({
      id: code.id * 100 + 3,
      amount: 5,
      currency: STORE_CURRENCY,
      method: 'CREDIT',
      state: 'CANCELLED',
      note: null,
      paidBy: null,
      paidAt: null,
      createdAt: NOW - 2 * DAY,
    });
  }
  return list;
}

function creatorReport({ query, volume }) {
  const from = numberOf(query.from);
  const to = numberOf(query.to);
  const creators = creatorRows(volume).map((code) => {
    const all = creatorEarnings(volume, code);
    const ranged = all.filter(
      (e) => (from === null || e.createdAt >= from) && (to === null || e.createdAt < to),
    );
    const of = (list, state) => sum(list.filter((e) => e.state === state).map((e) => e.amount));
    const paidOut = sum(
      creatorPayouts(volume, code)
        .filter((p) => p.state === 'PAID')
        .map((p) => p.amount),
    );
    const payable = money(of(all, 'AVAILABLE') + of(all, 'PAID'));
    return {
      id: code.id,
      creator: code.creator,
      code: code.code,
      uses: ranged.length,
      revenue: sum(ranged.map((e) => e.baseAmount)),
      earned: sum(ranged.filter((e) => e.state !== 'REVERSED').map((e) => e.amount)),
      pending: of(ranged, 'PENDING'),
      reversed: sum(ranged.map((e) => e.reversedAmount)),
      paidOut,
      available: money(payable - paidOut),
    };
  });
  return ok({ creators, currency: STORE_CURRENCY });
}

const creatorOf = (volume, rawId) => creatorRows(volume).find((code) => code.id === idOf(rawId));

function creatorEarningsBody({ params, query, volume }) {
  const code = creatorOf(volume, params.id);
  if (!code) return NOT_FOUND;
  const list = creatorEarnings(volume, code).filter((e) => !query.state || e.state === query.state);
  return listBody('earnings', 'earningCount', list, query, PAGE_SIZE);
}

function creatorPayoutsBody({ params, volume }) {
  const code = creatorOf(volume, params.id);
  return code ? ok({ payouts: creatorPayouts(volume, code) }) : NOT_FOUND;
}

const GIFT_NAMES = [
  'Halloween giveaway',
  'Discord booster reward',
  'Vote party prize',
  'Apology for the downtime on the Skyblock server (September)',
  'Twitch drop',
  'Staff welcome pack',
];

export function giftRows(volume) {
  const n = countFor(volume, 5, 31);
  const products = productRows(volume);
  return rows('gift', n, (i, random) => {
    const type = products.length ? ['PRODUCT', 'CREDIT', 'RANDOM'][i % 3] : 'CREDIT';
    const productId = type === 'PRODUCT' ? products[i % products.length].id : null;
    const productIds =
      type === 'RANDOM'
        ? products.slice(i % products.length, (i % products.length) + 3).map((p) => p.id)
        : null;
    const redeemLimit = i % 4 === 0 ? null : 25 * (1 + (i % 4));
    return {
      id: 700 + n - i,
      name: GIFT_NAMES[i % GIFT_NAMES.length],
      code: `GIFT-${(0x1a2b3c + i * 7919).toString(36).toUpperCase()}`,
      type,
      productId,
      creditAmount: type === 'CREDIT' ? [250, 500, 1000][i % 3] : null,
      productIds,
      redeemLimit,
      customerRedeemLimit: 1,
      usedCount: Math.floor(random() * (redeemLimit ?? 120)),
      status: statusOf(random),
      ...window(i, random),
      ...stamps(i, random),
      productName: productId ? products[productId - 1].name : null,
      productNames: productIds ? productIds.map((id) => products[id - 1].name) : null,
    };
  });
}

/** `search` matches the code or the name / creator, `status` the status (PromotionAdminService.list). */
function promotionList(key, countKey, build, texts) {
  return ({ query, volume }) => {
    const list = build(volume).filter(
      (row) =>
        (!query.status || row.status === query.status) && matches(query.search, ...texts(row)),
    );
    return listBody(key, countKey, list, query, PAGE_SIZE);
  };
}

const REDEEMABLE = { coupons: couponRows, gifts: giftRows, 'creator-codes': creatorRows };

function redemptions(kind) {
  return ({ params, query, volume }) => {
    const row = REDEEMABLE[kind](volume).find((r) => r.id === idOf(params.id));
    if (!row) return NOT_FOUND;
    const orders = orderRows(volume);
    const n = Math.min(row.usedCount, orders.length, volume === 'many' ? 37 : 5);
    const list = rows(`redemption:${kind}:${row.id}`, n, (i, random) => {
      const order = orders[(row.id * 7 + i * 3) % orders.length];
      return {
        orderId: order.id,
        playerUsername: i % 6 === 5 ? null : order.playerUsername,
        amount: kind === 'gifts' ? 0 : money(order.totalPrice * 0.1),
        currency: order.currency,
        state: weighted(random(), [
          ['APPLIED', 8],
          ['HELD', 1],
          ['RELEASED', 1],
        ]),
        createdAt: order.createdAt,
      };
    });
    // one row per order (the modal keys its rows by orderId)
    const unique = list.filter((r, at) => list.findIndex((o) => o.orderId === r.orderId) === at);
    return listBody('redemptions', 'redemptionCount', unique, query, PAGE_SIZE);
  };
}

// ================================================================================== comparisons

const COMPARISON_NAMES = [
  'Rank comparison',
  'Crate keys',
  'Starter kits',
  'Which pass is right for you? (Fly, Island Expansion and the seasonal battle pass)',
  'Pets',
  'Cosmetics',
];
const FEATURES = [
  '/fly',
  'Homes',
  'Coloured chat',
  'Priority queue',
  'Monthly crate keys',
  'Custom prefix',
  '/nick',
];
const CELLS = ['yes', 'no', '3', 'Unlimited', '5 per month', 'yes', 'no'];

export function comparisonRows(volume) {
  const n = countFor(volume, 3, 23);
  const products = productRows(volume);
  return rows('comparison', n, (i, random) => {
    const columns = Math.min(products.length, 2 + (i % 3));
    const productIds = Array.from(
      { length: columns },
      (_, c) => products[(i * 2 + c) % products.length].id,
    );
    const featureCount = 3 + (i % 4);
    const features = Array.from({ length: featureCount }, (_, f) => ({
      id: (i + 1) * 100 + f,
      name: FEATURES[(i + f) % FEATURES.length],
    }));
    const cellValues = {};
    for (const feature of features)
      for (const pid of productIds)
        cellValues[`${feature.id}-${pid}`] = CELLS[Math.floor(random() * CELLS.length)];
    const round = Math.floor(i / COMPARISON_NAMES.length);
    const createdAt = NOW - (i + 3) * 6 * DAY;
    return {
      id: n - i,
      name: COMPARISON_NAMES[i % COMPARISON_NAMES.length] + (round ? ` (${round + 1})` : ''),
      status: weighted(random(), [
        ['ACTIVE', 5],
        ['INACTIVE', 2],
        ['HIDDEN', 1],
      ]),
      priority: n - i,
      productIds: i % 4 === 3 ? [...productIds, null] : productIds,
      features,
      cellValues,
      createdAt,
      updatedAt: createdAt + Math.floor(random() * 5 * DAY),
    };
  });
}

function comparisonsBody({ query, volume }) {
  const products = productRows(volume);
  const list = comparisonRows(volume)
    .filter((c) => (!query.status || c.status === query.status) && matches(query.search, c.name))
    .map((c) => ({
      id: c.id,
      name: c.name,
      status: c.status,
      priority: c.priority,
      products: c.productIds.filter((id) => id !== null).map((id) => products[id - 1].name),
      createdAt: c.createdAt,
      updatedAt: c.updatedAt,
    }));
  return listBody('comparisons', 'comparisonCount', list, query, PAGE_SIZE);
}

function comparisonBody({ params, volume }) {
  const c = comparisonRows(volume).find((row) => row.id === idOf(params.id));
  if (!c) return NOT_FOUND;
  return ok({
    id: c.id,
    name: c.name,
    status: c.status,
    priority: c.priority,
    selectedProducts: c.productIds,
    features: c.features,
    cellValues: c.cellValues,
  });
}

// ================================================================================== goals

const GOALS = [
  [
    'Monthly server costs',
    'Help us keep the dedicated machine and the DDoS protection online.',
    'REVENUE',
    'MONTHLY',
  ],
  [
    'New Skyblock season',
    'When the goal is reached the next Skyblock season opens a week early for everyone.',
    'REVENUE',
    'ONE_TIME',
  ],
  ['100 orders this week', null, 'ORDERS', 'WEEKLY'],
  [
    'Crate key frenzy: sell 500 Legendary and Mythic keys to unlock the community double-drop weekend',
    'Every key counts.',
    'PRODUCT_SALES',
    'ONE_TIME',
  ],
  ['Summer build contest prize pool', 'Finished in August.', 'REVENUE', 'ONE_TIME'],
];

export function goalRows(volume) {
  const n = countFor(volume, 3, 12);
  const products = productRows(volume);
  return rows('goal', n, (i, random) => {
    const [name, description, baseMetric, period] = GOALS[i % GOALS.length];
    const metric = baseMetric === 'PRODUCT_SALES' && !products.length ? 'ORDERS' : baseMetric;
    const round = Math.floor(i / GOALS.length);
    const target = metric === 'REVENUE' ? [250, 1000, 600][i % 3] : metric === 'ORDERS' ? 100 : 500;
    const done = i % GOALS.length === 4;
    const ratio = done ? 1 : i === 0 ? 0.64 : random() * 1.1;
    const progress =
      metric === 'REVENUE'
        ? money(Math.min(target, target * ratio))
        : Math.min(target, Math.floor(target * ratio));
    const completed = progress >= target;
    const createdAt = NOW - (i + 1) * 20 * DAY;
    return {
      id: i + 1,
      name: name + (round ? ` #${round + 1}` : ''),
      description,
      metric,
      productIds: metric === 'PRODUCT_SALES' ? products.slice(0, 2).map((p) => p.id) : null,
      target,
      progress,
      percent: Math.min(100, Math.floor((progress / target) * 100)),
      currency: metric === 'REVENUE' ? STORE_CURRENCY : null,
      period,
      periodStart:
        period === 'MONTHLY'
          ? Date.UTC(2026, 9, 1)
          : period === 'WEEKLY'
            ? Date.UTC(2026, 8, 28)
            : null,
      startsAt: period === 'ONE_TIME' ? createdAt : null,
      endsAt: period === 'ONE_TIME' && !done ? NOW + (20 + i * 5) * DAY : null,
      status:
        completed && period === 'ONE_TIME' ? 'COMPLETED' : i % 6 === 5 ? 'INACTIVE' : 'ACTIVE',
      showOnStore: i % 4 !== 3,
      completedAt: completed ? NOW - (i + 1) * DAY : null,
      position: i,
      createdAt,
      updatedAt: createdAt + Math.floor(random() * 10 * DAY),
    };
  });
}

// ================================================================================== routes

export const routes = [
  get('/orders/:id', ({ params, volume }) => orderDetail(volume, params.id)),
  get('/payments/:paymentId/events', paymentEvents),

  get('/stats', ({ query, volume }) => statsBody(volume, query)),
  get('/health', ({ volume }) => healthBody(volume)),

  get('/credits/accounts', creditAccountsBody),
  get('/credits/accounts/:userId', creditAccountBody),
  get('/credits/transactions', creditTransactionsBody),

  get('/blocks', blocksBody),

  get(
    '/discounts',
    promotionList('discounts', 'discountCount', discountRows, (row) => [row.name]),
  ),
  get(
    '/coupons',
    promotionList('coupons', 'couponCount', couponRows, (row) => [row.code, row.name]),
  ),
  get('/coupons/:id/redemptions', redemptions('coupons')),
  get(
    '/creator-codes',
    promotionList('creatorCodes', 'creatorCodeCount', creatorRows, (row) => [
      row.code,
      row.creator,
    ]),
  ),
  get('/creator-codes/report', creatorReport),
  get('/creator-codes/:id/earnings', creatorEarningsBody),
  get('/creator-codes/:id/payouts', creatorPayoutsBody),
  get('/creator-codes/:id/redemptions', redemptions('creator-codes')),

  get(
    '/gifts',
    promotionList('gifts', 'giftCount', giftRows, (row) => [row.code, row.name]),
  ),
  get('/gifts/:id/redemptions', redemptions('gifts')),

  get('/comparisons', comparisonsBody),
  get('/comparisons/:id', comparisonBody),

  get('/goals', ({ volume }) => ok({ goals: goalRows(volume) })),
];

export const pages = [
  { side: 'panel', label: 'Overview', href: '/market' },
  { side: 'panel', label: 'Overview (charts)', href: '/market?view=chart' },
  { side: 'panel', label: 'Order detail', href: '/market/orders/detail/1007' },
  { side: 'panel', label: 'Order detail (older order)', href: '/market/orders/detail/1001' },
  { side: 'panel', label: 'Credits', href: '/market/credits' },
  { side: 'panel', label: 'Credit transactions', href: '/market/credits?section=transactions' },
  { side: 'panel', label: 'Credit account', href: '/market/credits/account/1' },
  { side: 'panel', label: 'Blocks', href: '/market/blocks' },
  { side: 'panel', label: 'Discounts', href: '/market/discounts?section=general' },
  { side: 'panel', label: 'Coupons', href: '/market/discounts?section=coupons' },
  { side: 'panel', label: 'Creator codes', href: '/market/discounts?section=creators' },
  { side: 'panel', label: 'Creator payouts', href: '/market/discounts?section=payouts' },
  { side: 'panel', label: 'Creator detail', href: '/market/discounts/creator/1' },
  { side: 'panel', label: 'Gifts', href: '/market/gifts' },
  { side: 'panel', label: 'Comparisons', href: '/market/comparisons' },
  { side: 'panel', label: 'Edit comparison', href: '/market/comparisons/create-comparison?id=1' },
  { side: 'panel', label: 'Goals', href: '/market/goals' },
];
